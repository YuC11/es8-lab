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

        // @focus-start 三个业务信号：上架时间衰减、销量对数加权、在售加权
        Map<String, Object> fsQuery = Json.map("function_score", Json.map(
                "query", baseQuery,
                "functions", List.of(
                        Json.map("gauss", Json.map("created_at", Json.map(
                                        "origin", "now-30d", "scale", "30d", "decay", 0.5)),
                                "weight", 2),
                        Json.map("field_value_factor", Json.map(
                                "field", "sales", "modifier", "log1p", "factor", 0.1)),
                        Json.map("filter", Json.map("term", Json.map("status", 1)),
                                "weight", 1.5)),
                "score_mode", "sum",
                "boost_mode", "multiply"));
        // @focus-end

        JsonNode base = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 10, "track_total_hits", true, "query", baseQuery));
        JsonNode boosted = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 10, "track_total_hits", true, "query", fsQuery));

        List<String> baseIds = ids(base);
        List<String> fsIds = ids(boosted);
        int overlap = 0;
        for (String id : fsIds) if (baseIds.contains(id)) overlap++;

        Map<String, String> titles = titles(base);
        titles.putAll(titles(boosted));

        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(5, fsIds.size()); i++) {
            rows.add(TableData.row(i + 1,
                    titleOf(baseIds, titles, i),
                    titleOf(fsIds, titles, i)));
        }

        String topId = fsIds.isEmpty() ? null : fsIds.get(0);
        JsonNode explain = null;
        String explainHint = "无结果，跳过 explain";
        if (topId != null) {
            explain = es.call("GET", "/" + SEARCH + "/_explain/" + topId,
                    Json.map("query", fsQuery)).body();
            explainHint = explain.path("explanation").path("description").asText("");
        }

        return LevelResult.builder("同一批命中、同样的 query，加入「上新 / 销量 / 在售」三个信号后，"
                        + "Top10 里有 " + (10 - overlap) + " 条被换掉；explain 能看到每个函数各贡献了多少分")
                .metrics(
                        new Metric("基线最高分", score(base.path("hits").path("max_score").asDouble(0)), "纯 BM25"),
                        new Metric("加权后最高分", score(boosted.path("hits").path("max_score").asDouble(0)),
                                "与原分相乘"),
                        new Metric("Top10 重合", overlap + " / 10", "排序确实变了"),
                        new Metric("命中数是否变化",
                                base.path("hits").path("total").path("value").asLong()
                                        == boosted.path("hits").path("total").path("value").asLong()
                                        ? "未变" : "变了（不符合预期）",
                                "function_score 只改排序"))
                .table(TableData.of(List.of("排名", "基线", "加权后"), rows))
                .json(Map.of("topId", topId == null ? "" : topId,
                        "explain", explain == null ? Map.of() : explain,
                        "functionScore", fsQuery))
                .notes(
                        "gauss 的 scale 决定衰减速度：30d 表示「30 天前的文档分数衰减到 decay 指定的比例」",
                        "field_value_factor 配 log1p 是为了压住销量长尾 —— 否则一个 10 万销量的爆款会把其他信号全部盖掉",
                        "score_mode=sum 先把三个函数加起来，boost_mode=multiply 再乘到 BM25 分上；换一种组合排序会完全不同",
                        "explain 里的「" + explainHint + "」说明这一分是怎么算出来的 —— 调相关性不能只靠猜")
                .build();
    }

    private List<String> ids(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode h : d.path("hits").path("hits")) out.add(h.path("_id").asText());
        return out;
    }

    private Map<String, String> titles(JsonNode d) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode h : d.path("hits").path("hits")) {
            out.put(h.path("_id").asText(), h.path("_source").path("title").asText("—"));
        }
        return out;
    }

    private static String titleOf(List<String> ids, Map<String, String> titles, int i) {
        if (i >= ids.size()) return "—";
        return titles.getOrDefault(ids.get(i), ids.get(i));
    }

    private static String score(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
