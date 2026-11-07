package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
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
 * M4-4 bool 的四个子句到底在做什么
 *
 * must    参与算分，影响 _score
 * filter  不参与算分，只过滤，可被缓存
 * should  满足则加分（无 must/filter 时至少满足一个）
 * must_not 排除，不参与算分
 *
 * 本关的对照方式：把**同一个价格条件**依次放进 filter / must / should / must_not，
 * 其余条件一字不改。命中数与 max_score 的变化，就是「位置决定语义」的全部证据。
 * 第二组对照单独把 bool 里的 should 拿出来，验证「有 must 时 should 只是加分、不是必需」，
 * 以及 minimum_should_match: 2 如何把它变回必要条件。
 */
@Component
public class M44BoolClauses implements LevelModule {

    private static final String INDEX = SeedService.V2;
    private static final String TITLE = "蓝牙耳机";
    private static final String CATEGORY = "数码影音";

    private final EsClient es;

    public M44BoolClauses(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-4";
    }

    @Override
    public LevelResult run() throws Exception {
        Map<String, Object> priceRange = Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800)));
        Map<String, Object> statusOne = Json.map("term", Json.map("status", 1));
        Map<String, Object> category = Json.map("term", Json.map("category", CATEGORY));
        Map<String, Object> titleMatch = Json.map("match", Json.map("title", TITLE));

        // @focus-start 同一个价格条件，四个位置：filter / must / should / must_not
        Run inFilter = run(Json.map("bool", Json.map(
                "must", Json.list(titleMatch),
                "filter", Json.list(category, priceRange, statusOne),
                "must_not", Json.list(Json.map("term", Json.map("brand", "二手"))))));

        Run inMust = run(Json.map("bool", Json.map(
                "must", Json.list(titleMatch, priceRange),
                "filter", Json.list(category, statusOne),
                "must_not", Json.list(Json.map("term", Json.map("brand", "二手"))))));

        Run inShould = run(Json.map("bool", Json.map(
                "must", Json.list(titleMatch),
                "filter", Json.list(category, statusOne),
                "should", Json.list(priceRange),
                "must_not", Json.list(Json.map("term", Json.map("brand", "二手"))))));

        Run inMustNot = run(Json.map("bool", Json.map(
                "must", Json.list(titleMatch),
                "filter", Json.list(category, statusOne),
                "must_not", Json.list(priceRange))));
        // @focus-end

        // @focus-start should 是不是必需：只有 should 时默认至少中一个，写 MSM:2 就都要中
        Run shouldOnly = run(Json.map("bool", Json.map("should", Json.list(
                Json.map("term", Json.map("tags", "爆款")),
                Json.map("range", Json.map("rating", Json.map("gte", 4.5)))))));

        Run shouldBoth = run(Json.map("bool", Json.map(
                "should", Json.list(
                        Json.map("term", Json.map("tags", "爆款")),
                        Json.map("range", Json.map("rating", Json.map("gte", 4.5)))),
                "minimum_should_match", 2)));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("基线：价格在 filter", "filter", fmt(inFilter.hits()), score(inFilter.maxScore()),
                "不算分、可缓存 —— 条件类过滤的正确位置"));
        rows.add(TableData.row("价格挪到 must", "must", fmt(inMust.hits()), score(inMust.maxScore()),
                "命中数与基线相同，但价格区间成了\"相关性因子\"，分数被抬高"));
        rows.add(TableData.row("价格挪到 should", "should", fmt(inShould.hits()), score(inShould.maxScore()),
                "should 是可选：不再过滤，命中数变成\"标题+类目\"的全集"));
        rows.add(TableData.row("价格挪到 must_not", "must_not", fmt(inMustNot.hits()), score(inMustNot.maxScore()),
                "语义反转：排除这个价格区间，命中数明显变少"));
        rows.add(TableData.row("只有 should（默认 MSM）", "should ×2", fmt(shouldOnly.hits()), score(shouldOnly.maxScore()),
                "没有 must/filter 时，默认 minimum_should_match = 1：中一个即命中"));
        rows.add(TableData.row("同一个 bool + MSM:2", "should ×2 + MSM 2", fmt(shouldBoth.hits()), score(shouldBoth.maxScore()),
                "把 should 变成必要条件：命中数骤降，分数也变高（两个条件都计分）"));

        long base = inFilter.hits();
        long shouldDelta = inShould.hits() - base;

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("inFilter", inFilter.raw());
        json.put("inMust", inMust.raw());
        json.put("inShould", inShould.raw());
        json.put("inMustNot", inMustNot.raw());
        json.put("shouldOnly", shouldOnly.raw());
        json.put("shouldBoth", shouldBoth.raw());

        return LevelResult.builder("同一个价格条件放进 filter 命中 " + fmt(base)
                        + " 条，放进 must 还是 " + fmt(inMust.hits()) + " 条（分数变了），放进 should 变成 "
                        + fmt(inShould.hits()) + " 条（不再过滤），放进 must_not 只剩 " + fmt(inMustNot.hits())
                        + " 条 —— 位置决定语义，not 写法")
                .metrics(
                        new Metric("价格在 filter", fmt(inFilter.hits()), "基准：条件类过滤的正确位置"),
                        new Metric("价格在 must", fmt(inMust.hits()), "命中不变，分数从 " + score(inFilter.maxScore())
                                + " 变 " + score(inMust.maxScore())),
                        new Metric("价格在 should", fmt(inShould.hits()), "可选条件：+" + fmt(shouldDelta) + " 条"),
                        new Metric("价格在 must_not", fmt(inMustNot.hits()), "语义反转：反向过滤"),
                        new Metric("should 默认 MSM", fmt(shouldOnly.hits()), "中一个即命中"),
                        new Metric("MSM:2", fmt(shouldBoth.hits()), "两个都要中"))
                .table(new TableData(List.of("变体", "价格条件位置", "命中", "max_score", "说明"), rows))
                .json(json)
                .notes(
                        "must 里的条件会参与打分：把 range price 从 filter 挪到 must 后，价格区间也成了\"相关性因子\"，分数分布被扭曲——这是实践中最常见的误用。",
                        "should 的两副面孔：bool 里已有 must 或 filter 时它是纯加分项（可一个都不中）；一个都没有时默认 minimum_should_match = 1。",
                        "minimum_should_match 是\"把 should 变必需\"的正确开关；把它写成 2 之后，shouldOnly 的那两个条件必须同时满足。",
                        "filter 的两个好处：不算分（省 CPU）、结果可进入 filter cache（重复查询直接复用）。",
                        "must_not 也不参与算分，等价于 filter 的取反，常用于排除下架/黑名单。")
                .build();
    }

    private Run run(Map<String, Object> query) {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 3,
                "track_total_hits", true,
                "_source", Json.list("title", "price", "category", "tags", "brand"),
                "query", query));

        JsonNode maxScoreNode = r.path("hits").path("max_score");

        List<Map<String, Object>> tops = new ArrayList<>();
        for (JsonNode h : r.path("hits").path("hits")) {
            tops.add(Json.map(
                    "title", h.path("_source").path("title").asText(),
                    "score", Timing.round(h.path("_score").asDouble(0), 3)));
        }

        return new Run(
                r.path("hits").path("total").path("value").asLong(),
                maxScoreNode.isNumber() ? maxScoreNode.asDouble() : null,
                r, tops);
    }

    private static String score(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private record Run(long hits, Double maxScore, JsonNode raw, List<Map<String, Object>> tops) {
    }
}
