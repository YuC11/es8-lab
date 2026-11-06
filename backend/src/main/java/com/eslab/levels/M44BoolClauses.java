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
 */
@Component
public class M44BoolClauses implements LevelModule {

    private static final String INDEX = SeedService.V2;

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
        // @focus-start 四个子句各就各位：语义过滤进 filter，相关性进 must，偏好进 should，排除进 must_not
        Run full = run(Json.map("bool", Json.map(
                "must", Json.list(
                        Json.map("match", Json.map("title", "蓝牙耳机"))),
                "filter", Json.list(
                        Json.map("term", Json.map("category", "数码影音")),
                        Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))),
                        Json.map("term", Json.map("status", 1))),
                "should", Json.list(
                        Json.map("term", Json.map("tags", "爆款")),
                        Json.map("range", Json.map("rating", Json.map("gte", 4.5)))),
                "must_not", Json.list(
                        Json.map("term", Json.map("brand", "二手"))))));
        // @focus-end

        // 对照组：把同一个价格条件从 filter 挪到 must，看 _score 的变化
        Run mustVersion = run(Json.map("bool", Json.map(
                "must", Json.list(
                        Json.map("match", Json.map("title", "蓝牙耳机")),
                        Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800)))),
                "filter", Json.list(
                        Json.map("term", Json.map("category", "数码影音"))))));

        List<List<Object>> rows = new ArrayList<>();
        for (Map<String, Object> t : full.tops()) {
            rows.add(TableData.row("Top", t.get("title"), t.get("score")));
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("full", asJson(full));
        json.put("mustVersion", asJson(mustVersion));

        return LevelResult.builder("同一个条件放在 filter 还是 must，命中数完全一样（" + full.hits()
                        + " 条），但只有 must 里的条件会影响 _score——这就是该不该算分的分界线")
                .metrics(
                        new Metric("完整 bool 命中", full.hits(), null),
                        new Metric("最高分（含 should 加分）", scoreText(full.maxScore()), null),
                        new Metric("最高分（价格在 must）", scoreText(mustVersion.maxScore()), null),
                        new Metric("should 的作用", "加分不筛选", "因为已有 must，should 变为可选"))
                .table(new TableData(List.of("结果", "标题", "_score"), rows))
                .json(json)
                .notes(
                        "must 里的条件会参与打分：把 range price 从 filter 挪到 must 后，价格区间也成了\"相关性因子\"，分数分布被扭曲——这是实践中最常见的误用。",
                        "只要 bool 里有 must 或 filter，should 就退化为\"加分项\"而不是\"必须满足\"。想让 should 成为必要条件，设置 minimum_should_match: 1。",
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
                tops);
    }

    /** 与 TS 的 maxScore?.toFixed(3) ?? '—' 一致 */
    private static String scoreText(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static Map<String, Object> asJson(Run r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hits", r.hits());
        m.put("maxScore", r.maxScore());
        m.put("tops", r.tops());
        return m;
    }

    private record Run(long hits, Double maxScore, List<Map<String, Object>> tops) {
    }
}
