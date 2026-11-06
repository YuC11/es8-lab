package com.eslab.experiments;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.ExperimentModule;
import com.eslab.model.ExperimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.List;

/**
 * 实验 B：filter vs must（同一谓词，只改子句位置）
 *
 * must 里的条件参与算分，每个命中文档都要分配评分器；filter 只判真假、不算分，
 * 且结果可以进 request cache。两者查询语义完全相同，差别只在「要不要花钱算分」。
 */
@Component
public class ExpFilterVsMust implements ExperimentModule {

    private static final String V2 = "eslab-products-v2";

    /** 两个条件原样复用：只有落在 must 还是 filter 上不同 */
    private static final List<Object> PREDICATES = Json.list(
            Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 800))),
            Json.map("term", Json.map("status", 1)));

    private final EsClient es;

    public ExpFilterVsMust(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "exp-filter-vs-must";
    }

    @Override
    public String title() {
        return "filter vs must：算分要不要花钱";
    }

    @Override
    public String question() {
        return "完全相同的两个条件，放进 must 与放进 filter，耗时差多少？request cache 有没有生效？";
    }

    @Override
    public ExperimentResult run() throws Exception {
        JsonNode before = es.get("/_nodes/stats/indices/request_cache");
        // 两侧同口径：5 轮正式样本 + 1 轮预热，A/B 交替执行
        Timing.Pair pair = Timing.pair(5, 1, round -> byMust(), round -> byFilter());
        Timing.Sample a = pair.a();
        Timing.Sample b = pair.b();
        JsonNode after = es.get("/_nodes/stats/indices/request_cache");

        Iterator<String> nodeIds = before.path("nodes").fieldNames();
        String nodeId = nodeIds.hasNext() ? nodeIds.next() : "";
        long hitDelta = 0;
        if (!nodeId.isEmpty()) {
            hitDelta = after.path("nodes").path(nodeId).path("indices").path("request_cache").path("hit_count").asLong()
                    - before.path("nodes").path(nodeId).path("indices").path("request_cache").path("hit_count").asLong();
        }

        Timing.Compare cmp = Timing.compare(a, b);
        double absDelta = Timing.round(a.median() - b.median());
        // 单机单次 took 抖动可到 ±5ms，落在这个区间内不能宣称「谁更快」
        boolean noise = Math.abs(absDelta) <= 5;

        String conclusion;
        if (noise) {
            conclusion = "两者持平（must " + num(a.median()) + "ms vs filter " + num(b.median()) + "ms，差 "
                    + num(absDelta) + "ms 落在噪声内）——本规模下算分开销不显著，不能据此说谁更快";
        } else if (b.median() < a.median()) {
            conclusion = "filter 更快：" + num(a.median()) + "ms → " + num(b.median())
                    + "ms（省 " + num(cmp.improvementPct()) + "%）";
        } else {
            conclusion = "filter 更慢：耗时是 must 的 "
                    + num(Timing.round(b.median() / Math.max(a.median(), 0.1), 1))
                    + " 倍（" + num(a.median()) + "ms → " + num(b.median()) + "ms）";
        }

        return new ExperimentResult(
                id(),
                title(),
                question(),
                new ExperimentResult.Side(
                        "must（参与算分）",
                        a.median(),
                        "ms",
                        a.runs(),
                        "每个命中文档都要分配评分器"),
                new ExperimentResult.Side(
                        "filter（不算分，可缓存）",
                        b.median(),
                        "ms",
                        b.runs(),
                        "本次采样期间 request cache 命中 +" + hitDelta),
                new ExperimentResult.Delta(cmp.speedup(), cmp.improvementPct()),
                conclusion,
                List.of(
                        "filter 的收益主要来自\"不分配评分器\"和\"结果可缓存\"，在高并发、大结果集时才显著。",
                        "request cache 只缓存 size=0 或聚合类请求（且 key 是整个查询体），普通查询不一定进缓存，命中增量为 0 属正常。",
                        "本机单分片、数据在页缓存中，CPU 差异会被 IO 与网络噪声淹没。"));
    }

    private double byMust() {
        // @focus-start 同一组条件放进 must：参与算分
        JsonNode r = es.post("/" + V2 + "/_search", Json.map(
                "size", 20,
                "track_total_hits", false,
                "query", Json.map("bool", Json.map("must", PREDICATES))));
        // @focus-end
        return r.path("took").asDouble();
    }

    private double byFilter() {
        // @focus-start 同一组条件放进 filter：只判真假，不算分
        JsonNode r = es.post("/" + V2 + "/_search", Json.map(
                "size", 20,
                "track_total_hits", false,
                "query", Json.map("bool", Json.map("filter", PREDICATES))));
        // @focus-end
        return r.path("took").asDouble();
    }

    /** TS 的模板字符串不会输出无意义的小数位，这里保持同样观感 */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
