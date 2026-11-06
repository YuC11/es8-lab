package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M4-5 子句参数：改命中集，还是改排序
 *
 * 同一个查询类型，参数不同，效果分两类：
 *   改「谁算命中」：operator（or/and）、minimum_should_match
 *   改「谁排前面」：boost（只影响分数，一行命中都不会变）
 * 分清这两类，才能判断一个参数该不该用、用了会动到什么。
 */
@Component
public class M45QueryParams implements LevelModule {

    private static final String INDEX = SeedService.V2;
    private static final String THREE_WORDS = "蓝牙 降噪 耳机";

    private final EsClient es;

    public M45QueryParams(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-5";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 前三条只改「命中条件」，第四条只改「排序权重」
        R or = run(Json.map("match", Json.map("title", THREE_WORDS)));
        R msm2 = run(Json.map("match", Json.map("title",
                Json.map("query", THREE_WORDS, "minimum_should_match", "2"))));
        R and = run(Json.map("match", Json.map("title",
                Json.map("query", THREE_WORDS, "operator", "and"))));
        R plain = run(Json.map("bool", Json.map("should", Json.list(
                Json.map("term", Json.map("tags", "爆款")),
                Json.map("term", Json.map("category", "数码影音"))))));
        R boosted = run(Json.map("bool", Json.map("should", Json.list(
                Json.map("term", Json.map("tags", Json.map("value", "爆款", "boost", 5))),
                Json.map("term", Json.map("category", "数码影音"))))));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("operator: or（默认）", "match title=" + THREE_WORDS,
                String.format(Locale.ROOT, "%,d", or.total()), score(or.maxScore()),
                "命中任一关键词即算命中 —— 召回最大、精度最低"));
        rows.add(TableData.row("minimum_should_match: 2", "至少命中 3 个词中的 2 个",
                String.format(Locale.ROOT, "%,d", msm2.total()), score(msm2.maxScore()),
                "按比例收紧：介于 or 与 and 之间"));
        rows.add(TableData.row("operator: and", "三个词必须全中",
                String.format(Locale.ROOT, "%,d", and.total()), score(and.maxScore()),
                "召回最小、精度最高，最容易「搜不到」"));
        rows.add(TableData.row("boost: 1（默认）", "bool.should 两个条件不加权",
                String.format(Locale.ROOT, "%,d", plain.total()), score(plain.maxScore()),
                "基准"));
        rows.add(TableData.row("boost: 5", "tags 条件权重 ×5",
                String.format(Locale.ROOT, "%,d", boosted.total()), score(boosted.maxScore()),
                "命中数一模一样，只有分数被抬高 —— 改变的是排序，不是命中"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("operator: or", or.raw());
        json.put("minimum_should_match: 2", msm2.raw());
        json.put("operator: and", and.raw());
        json.put("boost: 1", plain.raw());
        json.put("boost: 5", boosted.raw());

        return LevelResult.builder("operator 与 minimum_should_match 改的是「谁算命中」，boost 改的是「谁排前面」"
                        + " —— 前者能让命中数从 " + String.format(Locale.ROOT, "%,d", or.total())
                        + " 掉到 " + String.format(Locale.ROOT, "%,d", and.total())
                        + "，后者一行命中都不会动")
                .metrics(
                        new Metric("operator: or", String.format(Locale.ROOT, "%,d", or.total()), "默认行为"),
                        new Metric("minimum_should_match: 2", String.format(Locale.ROOT, "%,d", msm2.total()),
                                "三词里至少两个"),
                        new Metric("operator: and", String.format(Locale.ROOT, "%,d", and.total()), "三词全中"),
                        new Metric("boost 前后的最高分", score(plain.maxScore()) + " → " + score(boosted.maxScore()),
                                "命中数不变：" + String.format(Locale.ROOT, "%,d", boosted.total())))
                .table(new TableData(List.of("参数", "写法", "命中", "最高分", "它改变了什么"), rows))
                .json(json)
                .notes(
                        "match 默认是 operator: or，只要命中任意一个词就算 —— 中文短句经常因此召回一堆不相关的文档。",
                        "minimum_should_match 支持百分比（如 75%），比 operator 更灵活：词数变化时阈值跟着变。",
                        "boost 只改变打分权重，不会让原本不命中的文档命中，也不会让命中的掉出去 —— 它是排序工具，不是过滤工具。",
                        "真正要过滤「必须有」的条件，用 bool.filter；boost 只用于「都命中时谁排前面」。",
                        "同名参数在不同查询里位置不同：match 的子参数写在 {query, operator} 里，term 的写在 {value, boost} 里。")
                .build();
    }

    private R run(Map<String, Object> query) {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1,
                "track_total_hits", true,
                "query", query));
        JsonNode hits = r.path("hits");
        JsonNode max = hits.path("max_score");
        return new R(
                hits.path("total").path("value").asLong(),
                max.isNumber() ? max.asDouble() : null,
                r);
    }

    /** 分数保留三位；null 说明没有命中 */
    private static String score(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.3f", v);
    }

    private record R(long total, Double maxScore, JsonNode raw) {
    }
}
