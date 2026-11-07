package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m9-3：function_score。
 *
 * 纯文本相关性回答「这条文档和 query 有多像」，function_score 让业务信号参与排序：
 * 上架越新、卖得越多、还在售，就往前排 —— 但依然不改「谁算命中」。
 *
 * 本关的关键是「逐个启用」：基线 → 只加上新 → 只加销量 → 只加在售 → 三个全开。
 * 用同一个文档的 _explain 分数，把每个函数各自贡献了多少分算出来，而不是一次性全开看个总分。
 */
@Component
public class M93FunctionScore implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;
    private static final String QUERY = "蓝牙耳机";

    private final EsClient es;
    private final LabData lab;

    public M93FunctionScore(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-3";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.ensureSearch();

        Map<String, Object> baseQuery = Json.map("multi_match", Json.map(
                "query", QUERY,
                "fields", List.of("title^2", "description"),
                "type", "best_fields"));

        // @focus-start 三个业务信号，各自可以单独启用
        Map<String, Object> gauss = Json.map("gauss", Json.map("created_at", Json.map(
                        "origin", "now-30d", "scale", "30d", "decay", 0.5)),
                "weight", 2);
        // 对照组：语料最新也是几个月前，30 天窗口会把老数据压成 0；把窗口放宽到一年才看得出「渐变」
        Map<String, Object> gaussWide = Json.map("gauss", Json.map("created_at", Json.map(
                        "origin", "now-365d", "scale", "365d", "decay", 0.5)),
                "weight", 2);
        Map<String, Object> fvf = Json.map("field_value_factor", Json.map(
                "field", "sales", "modifier", "log1p", "factor", 0.1));
        Map<String, Object> onSale = Json.map("filter", Json.map("term", Json.map("status", 1)),
                "weight", 1.5);
        // @focus-end

        Map<String, Object> onlyGauss = fs(baseQuery, List.of(gauss));
        Map<String, Object> wideGauss = fs(baseQuery, List.of(gaussWide));
        Map<String, Object> onlySales = fs(baseQuery, List.of(fvf));
        Map<String, Object> onlyStatus = fs(baseQuery, List.of(onSale));
        Map<String, Object> all = fs(baseQuery, List.of(gauss, fvf, onSale));

        JsonNode base = search(baseQuery);
        JsonNode boostAll = search(all);

        String docId = base.path("hits").path("hits").path(0).path("_id").asText("");
        String docTitle = base.path("hits").path("hits").path(0).path("_source").path("title").asText("—");

        // 同一个文档在五种组合下的得分：差值是各函数的贡献
        double sBase = explainScore(docId, baseQuery);
        double sGauss = explainScore(docId, onlyGauss);
        double sWide = explainScore(docId, wideGauss);
        double sSales = explainScore(docId, onlySales);
        double sStatus = explainScore(docId, onlyStatus);
        double sAll = explainScore(docId, all);

        int overlap = overlap(base, boostAll);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("基线（纯 BM25）", score(sBase), "—", "只有文本相关性"));
        rows.add(TableData.row("+ 上架 gauss(scale 30d)", score(sGauss),
                delta(sGauss - sBase), "窗口太窄：语料最新也是几个月前，衰减到接近 0"));
        rows.add(TableData.row("+ 上架 gauss(scale 365d)", score(sWide),
                delta(sWide - sBase), "窗口对齐数据分布，才看得出「新旧有别」"));
        rows.add(TableData.row("+ 销量 log1p(factor 0.1)", score(sSales),
                delta(sSales - sBase), "log1p 压住长尾，避免爆款通吃"));
        rows.add(TableData.row("+ 在售 filter(weight 1.5)", score(sStatus),
                delta(sStatus - sBase), "命中 status=1 才加这一档"));
        rows.add(TableData.row("三个全开（sum × multiply）", score(sAll),
                delta(sAll - sBase), "score_mode=sum，boost_mode=multiply"));

        JsonNode explain = es.call("GET", "/" + SEARCH + "/_explain/" + docId,
                Json.map("query", all)).body();
        String explainHint = explain.path("explanation").path("description").asText("");

        boolean sameTotal = base.path("hits").path("total").path("value").asLong()
                == boostAll.path("hits").path("total").path("value").asLong();

        return LevelResult.builder("同一个文档（" + docTitle + "）：BM25 给 " + score(sBase)
                        + "，只加上新变成 " + score(sGauss) + "，只加销量 " + score(sSales)
                        + "，只加在售 " + score(sStatus) + "，三个全开 " + score(sAll)
                        + "；而命中数" + (sameTotal ? "一条都没变" : "变了（不符合预期）")
                        + " —— function_score 只改排序")
                .metrics(
                        new Metric("基线分", score(sBase), "纯 BM25 给这个文档的分"),
                        new Metric("上架信号（30d 窗口）", delta(sGauss - sBase),
                                "把老数据压到接近 0：窗口没对上数据分布"),
                        new Metric("上架信号（365d 窗口）", delta(sWide - sBase),
                                "窗口放宽后才有渐变"),
                        new Metric("销量信号贡献", delta(sSales - sBase), "log1p(sales) × 0.1"),
                        new Metric("在售信号贡献", delta(sStatus - sBase), "filter status=1 × 1.5"),
                        new Metric("三个全开", score(sAll), "score_mode=sum → multiply 到 BM25"),
                        new Metric("Top10 与基线重合", overlap + " / 10",
                                sameTotal ? "命中集不变，只有顺序变" : "命中集变了"))
                .table(TableData.of(List.of("函数组合", "该文档得分", "相对基线", "说明"), rows))
                .json(Json.map("topId", docId, "explain", explain,
                        "functionScore", all, "scores", Json.map(
                                "base", sBase, "gauss30d", sGauss, "gauss365d", sWide,
                                "sales", sSales, "status", sStatus, "all", sAll)))
                .notes(
                        "逐个启用才有信息量：三个一起开只能看到一个总分，说不出谁在起作用、谁的权重过大",
                        "gauss 的 scale 决定衰减速度：30d 表示「距离 origin 30 天时衰减到 decay 指定的比例」。本关第一行用 30d 窗口把老数据压到接近 0——"
                                + "因为语料最新也是几个月前，窗口根本没盖住数据；换成 365d 才看得出渐变。参数要对着数据分布调，不是越小越好",
                        "field_value_factor 配 log1p 是为了压住销量长尾 —— 否则一个 10 万销量的爆款会把其他信号全部盖掉",
                        "score_mode=sum 先把函数值加起来，boost_mode=multiply 再乘到 BM25 上；换一种组合排序会完全不同，而命中集始终不变",
                        "explain 里的「" + shortHint(explainHint) + "」说明这一分是怎么算出来的 —— 调相关性不能只靠猜")
                .build();
    }

    private Map<String, Object> fs(Map<String, Object> query, List<Map<String, Object>> functions) {
        return Json.map("function_score", Json.map(
                "query", query,
                "functions", functions,
                "score_mode", "sum",
                "boost_mode", "multiply"));
    }

    private JsonNode search(Map<String, Object> query) {
        return es.post("/" + SEARCH + "/_search", Json.map(
                "size", 10, "track_total_hits", true, "query", query));
    }

    /** 用 _explain 拿某个文档在指定查询下的总分 */
    private double explainScore(String docId, Map<String, Object> query) {
        if (docId == null || docId.isEmpty()) return 0;
        JsonNode r = es.call("GET", "/" + SEARCH + "/_explain/" + docId, Json.map("query", query)).body();
        return r.path("explanation").path("value").asDouble(0);
    }

    private static int overlap(JsonNode a, JsonNode b) {
        List<String> ids = new ArrayList<>();
        for (JsonNode h : a.path("hits").path("hits")) ids.add(h.path("_id").asText());
        int n = 0;
        for (JsonNode h : b.path("hits").path("hits")) {
            if (ids.contains(h.path("_id").asText())) n++;
        }
        return n;
    }

    private static String delta(double v) {
        String s = String.format(Locale.ROOT, "%.3f", Math.abs(v));
        return (v >= 0 ? "+" : "−") + s;
    }

    private static String score(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static String shortHint(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
