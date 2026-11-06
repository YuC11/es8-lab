package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M4-6 filter vs must：算分是要花钱的
 *
 * 同一个谓词，放 must 要遍历打分，放 filter 只做位图与求交，还能被缓存。
 * 本关用 5 轮采样取中位数对比，并观察 request cache 的命中情况。
 */
@Component
public class M46FilterVsMust implements LevelModule {

    private static final String INDEX = SeedService.V2;

    private final EsClient es;

    public M46FilterVsMust(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-6";
    }

    @Override
    public LevelResult run() throws Exception {
        JsonNode cacheBefore = es.get("/_nodes/stats/indices/request_cache");

        Timing.Sample mustTiming = Timing.sample(5, 1, round -> runMust());
        Timing.Sample filterTiming = Timing.sample(5, 1, round -> runFilter());

        JsonNode cacheAfter = es.get("/_nodes/stats/indices/request_cache");
        Iterator<String> nodeIds = cacheBefore.path("nodes").fieldNames();
        String nodeId = nodeIds.hasNext() ? nodeIds.next() : "";
        JsonNode before = cacheBefore.path("nodes").path(nodeId).path("indices").path("request_cache");
        JsonNode after = cacheAfter.path("nodes").path(nodeId).path("indices").path("request_cache");

        // 验证：must 版本有分数，filter 版本 _score 为 0
        JsonNode sample = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1,
                "query", Json.map("bool", Json.map(
                        "filter", Json.list(
                                Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))))))));
        JsonNode scoreNode = sample.path("hits").path("hits").path(0).path("_score");

        Timing.Compare cmp = Timing.compare(mustTiming, filterTiming);
        long absDelta = Math.round(mustTiming.median() - filterTiming.median());
        boolean noise = Math.abs(absDelta) <= 5;

        String verdict;
        if (noise) {
            verdict = "两者持平（差 " + absDelta + "ms，落在噪声内）——本规模下算分开销不显著，这本身就是结论";
        } else if (filterTiming.median() < mustTiming.median()) {
            verdict = "filter 快 " + num(Math.abs(cmp.improvementPct())) + "%";
        } else {
            double ratio = filterTiming.median() / Math.max(mustTiming.median(), 0.1);
            verdict = "filter 反而慢，耗时是 must 的 " + String.format(Locale.ROOT, "%.1f", ratio) + " 倍";
        }

        long hitDelta = after.path("hit_count").asLong() - before.path("hit_count").asLong();
        long missDelta = after.path("miss_count").asLong() - before.path("miss_count").asLong();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mustSamples", mustTiming.runs());
        json.put("filterSamples", filterTiming.runs());
        json.put("cache", Json.map("before", before, "after", after));
        if (scoreNode.isNumber()) json.put("sampleScore", scoreNode.asDouble());

        return LevelResult.builder("同条件同命中数：must 中位 " + num(mustTiming.median())
                        + "ms，filter 中位 " + num(filterTiming.median()) + "ms，" + verdict)
                .metrics(
                        new Metric("must 中位数", num(mustTiming.median()) + " ms",
                                "样本 " + joinRuns(mustTiming.runs())),
                        new Metric("filter 中位数", num(filterTiming.median()) + " ms",
                                "样本 " + joinRuns(filterTiming.runs())),
                        new Metric("提速", num(cmp.speedup()) + "×", null),
                        new Metric("request cache 命中增量", hitDelta, "miss 增量 " + missDelta))
                .chart(BarChartData.of("ms（中位数）", "5 轮采样，丢弃 1 轮预热",
                        new BarChartData.Item("must（算分）", mustTiming.median()),
                        new BarChartData.Item("filter（不算分）", filterTiming.median())))
                .json(json)
                .notes(
                        "本机单分片 20 万文档下差距可能不大，甚至波动——这恰恰是要强调的：**别拿一次 took 当结论**。数据量、命中比例、是否首次查询都会让结论反转。",
                        "filter 的真正价值在大规模数据与高并发下：不分配评分器、结果可进 filter cache，重复查询几乎零成本。",
                        "filter 命中的文档 _score 是 " + (scoreNode.isNumber() ? num(scoreNode.asDouble()) : "—")
                                + "（0 表示未算分）；只有需要\"按相关性排序\"时才应该用 must/match。",
                        "经验法则：能被\"是/否\"回答的条件（类目、状态、时间范围、价格区间）一律进 filter；只有\"像不像\"的判断（文本相关性）才进 must。")
                .build();
    }

    // @focus-start A 组：谓词放在 must —— 每个命中都要算分
    private long runMust() {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 20,
                "track_total_hits", false,
                "query", Json.map("bool", Json.map(
                        "must", Json.list(
                                Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))),
                                Json.map("term", Json.map("status", 1)))))));
        return r.path("took").asLong();
    }
    // @focus-end

    // @focus-start B 组：同一谓词放在 filter —— 不算分、可缓存
    private long runFilter() {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 20,
                "track_total_hits", false,
                "query", Json.map("bool", Json.map(
                        "filter", Json.list(
                                Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))),
                                Json.map("term", Json.map("status", 1)))))));
        return r.path("took").asLong();
    }
    // @focus-end

    private static String joinRuns(List<Double> runs) {
        List<String> parts = new ArrayList<>(runs.size());
        for (double v : runs) parts.add(num(v));
        return String.join(" / ", parts);
    }

    /** 复刻 JS 数字的默认字符串形式：整数不带 ".0" */
    private static String num(double v) {
        if (!Double.isInfinite(v) && !Double.isNaN(v) && v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }
}
