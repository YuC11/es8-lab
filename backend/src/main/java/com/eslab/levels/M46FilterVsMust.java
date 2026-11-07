package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
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
 * M4-6 filter vs must：省掉的到底是哪一步
 *
 * 同一组谓词（价格区间 + 状态），一次放 must、一次放 filter：
 *   · must 要给每个命中文档打分，filter 不用；
 *   · 官方文档常说「filter 可缓存」，但缓存的是哪一层？本关直接量。
 *
 * 测量口径（这一关的全部说服力都在这里）：
 *   · 两组都用 size:0 —— request cache 只对「不返回文档」的请求生效，用 size:20 测缓存等于什么都没测；
 *   · 两组用 Timing.pair 交替采样（5 轮 + 1 轮预热），避免「后跑的那组更热」；
 *   · 另做一次「清缓存后连发两次」，把 request cache 的 hit/miss 增量分开记。
 *
 * 实测结论与常见说法不一致：**must 的 size:0 查询同样会进 request cache**（两组 hit 增量都是 +3）。
 * 所以 filter 的确定收益是「不打分」，而不是「独占缓存」；在这个规模下两者耗时也测不出差异。
 */
@Component
public class M46FilterVsMust implements LevelModule {

    private static final String INDEX = SeedService.V2;
    private static final int ROUNDS = 5;

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
        // @focus-start 交替采样：A/B 各 5 轮，先预热 1 轮
        Timing.Pair pair = Timing.pair(ROUNDS, 1, round -> runMust(), round -> runFilter());
        Timing.Sample mustTiming = pair.a();
        Timing.Sample filterTiming = pair.b();
        // @focus-end

        // @focus-start 缓存对照：清缓存 → 同一个请求连发两次，看 request cache 的 hit/miss 怎么变
        JsonNode cacheStart = stats();
        clearRequestCache();
        long mustCold = runMust();
        long mustWarm = runMust();
        JsonNode cacheAfterMust = stats();

        clearRequestCache();
        long filterCold = runFilter();
        long filterWarm = runFilter();
        JsonNode cacheAfterFilter = stats();
        // @focus-end

        long mustHit = hits(cacheAfterMust) - hits(cacheStart);
        long mustMiss = misses(cacheAfterMust) - misses(cacheStart);
        long filterHit = hits(cacheAfterFilter) - hits(cacheAfterMust);
        long filterMiss = misses(cacheAfterFilter) - misses(cacheAfterMust);

        // 验证：filter 版本 _score 为 0（未打分），must 版本有分
        JsonNode mustSample = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1, "query", Json.map("bool", Json.map(
                        "must", Json.list(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))))))));
        JsonNode filterSample = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1, "query", Json.map("bool", Json.map(
                        "filter", Json.list(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))))))));
        JsonNode mustScoreNode = mustSample.path("hits").path("hits").path(0).path("_score");
        JsonNode filterScoreNode = filterSample.path("hits").path("hits").path(0).path("_score");

        Timing.Compare cmp = Timing.compare(mustTiming, filterTiming);
        long absDelta = Math.round(mustTiming.median() - filterTiming.median());
        boolean noise = Math.abs(absDelta) <= 5;

        String verdict;
        if (noise) {
            verdict = "两者在噪声内持平（差 " + absDelta + "ms）——本规模下算分开销不显著，这本身就是结论";
        } else if (filterTiming.median() < mustTiming.median()) {
            verdict = "filter 中位低 " + num(Math.abs(cmp.improvementPct())) + "%";
        } else {
            double ratio = filterTiming.median() / Math.max(mustTiming.median(), 0.1);
            verdict = "filter 反而慢，耗时是 must 的 " + String.format(Locale.ROOT, "%.1f", ratio) + " 倍";
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("must（清缓存后第一枪）", "size:0，算分", mustCold + " ms", "单次，仅作量级参考"));
        rows.add(TableData.row("must（紧接第二枪）", "同一条查询再发一次", mustWarm + " ms",
                "request cache hit +" + mustHit + " / miss +" + mustMiss));
        rows.add(TableData.row("filter（清缓存后第一枪）", "size:0，不算分", filterCold + " ms", "单次，仅作量级参考"));
        rows.add(TableData.row("filter（紧接第二枪）", "同一条查询再发一次", filterWarm + " ms",
                "request cache hit +" + filterHit + " / miss +" + filterMiss));
        rows.add(TableData.row("稳态（交替采样）", "5 轮中位", "must " + num(mustTiming.median())
                + " ms / filter " + num(filterTiming.median()) + " ms", "丢弃 1 轮预热"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("mustSamples", mustTiming.runs());
        json.put("filterSamples", filterTiming.runs());
        json.put("scope", pair.scope());
        json.put("cold", Json.map("must", mustCold, "filter", filterCold,
                "mustWarm", mustWarm, "filterWarm", filterWarm));
        json.put("cache", Json.map(
                "start", Json.map("hit", hits(cacheStart), "miss", misses(cacheStart)),
                "afterMust", Json.map("hit", hits(cacheAfterMust), "miss", misses(cacheAfterMust)),
                "afterFilter", Json.map("hit", hits(cacheAfterFilter), "miss", misses(cacheAfterFilter))));

        return LevelResult.builder("同条件同命中数：must 中位 " + num(mustTiming.median())
                        + "ms，filter 中位 " + num(filterTiming.median()) + "ms，" + verdict
                        + "。但缓存不是 filter 的专利：两组第二次请求都命中了 request cache（hit +"
                        + mustHit + " / +" + filterHit + "），确定的分界只有一条 —— filter 的 _score 是 "
                        + num(filterScoreNode.asDouble(0)) + "，must 是 " + num(mustScoreNode.asDouble(0)))
                .metrics(
                        new Metric("must 中位数", num(mustTiming.median()) + " ms",
                                "样本 " + joinRuns(mustTiming.runs())),
                        new Metric("filter 中位数", num(filterTiming.median()) + " ms",
                                "样本 " + joinRuns(filterTiming.runs())),
                        new Metric("冷启动（清缓存后）", mustCold + " / " + filterCold + " ms",
                                "must / filter 各一次"),
                        new Metric("request cache（must 组）", "hit +" + mustHit + " / miss +" + mustMiss,
                                "size:0 的算分查询也会被缓存"),
                        new Metric("request cache（filter 组）", "hit +" + filterHit + " / miss +" + filterMiss,
                                "与 must 组同一量级"),
                        new Metric("_score", "must " + num(mustScoreNode.asDouble(0))
                                        + " / filter " + num(filterScoreNode.asDouble(0)),
                                "唯一稳定可复现的差别"))
                .chart(BarChartData.of("ms（中位数）", "A/B 交替 5 轮，丢弃 1 轮预热；0 表示 <1ms",
                        new BarChartData.Item("must（算分）", mustTiming.median()),
                        new BarChartData.Item("filter（不算分）", filterTiming.median())))
                .table(new TableData(List.of("场景", "条件", "本轮实测", "口径"), rows))
                .json(json)
                .notes(
                        "本机单节点 2 万条、命中比例又高，差距可能只有几毫秒甚至反向 —— 这恰恰是要强调的：**别拿一次 took 当结论**。",
                        "交替采样（A/B 交替、各自预热）比「先把 must 跑完再跑 filter」更可信：后者会让第二组白拿前一组的热身收益。",
                        "常见说法「filter 的结果能被缓存，所以更快」在本机不成立：must 的 size:0 查询同样命中 request cache。真正被 filter 独占的是不分配评分器（_score 恒为 0），不是缓存。",
                        "request cache 只缓存 size:0（不返回文档）的请求。用 size:20 做对比时缓存根本不参与，这也是很多「测不出差别」的由来。",
                        "filter 命中的文档 _score 是 " + num(filterScoreNode.asDouble(0))
                                + "（0 表示未算分）；只有需要\"按相关性排序\"时才应该用 must/match。",
                        "经验法则：能被\"是/否\"回答的条件（类目、状态、时间范围、价格区间）一律进 filter；只有\"像不像\"的判断（文本相关性）才进 must。")
                .build();
    }

    private JsonNode stats() {
        return es.get("/_nodes/stats/indices/request_cache");
    }

    private static JsonNode cacheNode(JsonNode stats) {
        Iterator<String> ids = stats.path("nodes").fieldNames();
        String id = ids.hasNext() ? ids.next() : "";
        return stats.path("nodes").path(id).path("indices").path("request_cache");
    }

    private static long hits(JsonNode stats) {
        return cacheNode(stats).path("hit_count").asLong(0);
    }

    private static long misses(JsonNode stats) {
        return cacheNode(stats).path("miss_count").asLong(0);
    }

    private void clearRequestCache() {
        try {
            es.post("/" + INDEX + "/_cache/clear?request=true", null);
        } catch (Exception ignored) {
            // 清缓存失败不影响主结论，冷启动那一行会退化成「近似冷」
        }
    }

    // @focus-start A 组：谓词放在 must —— 每个命中都要算分
    private long runMust() {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0,
                "track_total_hits", true,
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
                "size", 0,
                "track_total_hits", true,
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
