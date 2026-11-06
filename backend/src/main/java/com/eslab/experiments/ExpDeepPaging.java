package com.eslab.experiments;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.ExperimentModule;
import com.eslab.model.ExperimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 实验 A：深分页 from+size vs search_after
 *
 * 比的是「到达同一位置的总成本」：from+size 一次调用，search_after 十次调用累加。
 * from+size 每分片要取 from+size 条候选再归并，越深越贵；search_after 用 PIT 保证
 * 排序在快照内稳定，每页只取 size 条，代价与页深无关。
 */
@Component
public class ExpDeepPaging implements ExperimentModule {

    private static final String V2 = "eslab-products-v2";
    private static final int SIZE = 100;

    private final EsClient es;

    public ExpDeepPaging(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "exp-deep-paging";
    }

    @Override
    public String title() {
        return "深分页：from+size vs search_after";
    }

    @Override
    public String question() {
        return "要拿到第 900–1000 条结果，两种翻页方式的代价差多少？";
    }

    @Override
    public ExperimentResult run() throws Exception {
        // 两侧同口径：5 轮正式样本 + 1 轮预热，A/B 交替执行
        Timing.Pair pair = Timing.pair(5, 1, round -> byFromSize(), round -> bySearchAfter());
        Timing.Sample a = pair.a();
        Timing.Sample b = pair.b();
        Timing.Compare cmp = Timing.compare(a, b);

        double perPage = b.median() / 10;

        // 注意口径：A 是 1 次请求，B 是 10 次请求，总耗时天然不可直接比。
        // 真正要讲的是「每页成本是否随深度增长」，所以这里同时给出总耗时与每页成本。
        String conclusion = "一次跳到第 900 条：" + num(a.median()) + "ms；search_after 连翻 10 页共 "
                + num(b.median()) + "ms（约 " + num(Timing.round(perPage)) + "ms/页）。"
                + "两者不是同口径：前者 1 次请求、后者 10 次，总耗时必然更高 —— "
                + "search_after 的价值是每页成本恒定，而不是总成本更低。";

        return new ExperimentResult(
                id(),
                title(),
                question(),
                new ExperimentResult.Side(
                        "from+size 一次跳页（from=900, size=" + SIZE + "）",
                        a.median(),
                        "ms",
                        a.runs(),
                        "每分片取 1000 条候选再归并；1 次请求"),
                new ExperimentResult.Side(
                        "search_after 翻 10 页（每页 " + SIZE + " 条）",
                        b.median(),
                        "ms",
                        b.runs(),
                        "每页只取 100 条，10 次请求累加，约 " + num(Timing.round(perPage)) + "ms/页"),
                new ExperimentResult.Delta(cmp.speedup(), cmp.improvementPct()),
                conclusion,
                List.of(
                        "口径提醒：A 是 1 次请求，B 是 10 次请求。要比「每页成本」，应拿 " + num(Timing.round(perPage))
                                + "ms/页 与 from 递增曲线（见 m6-1）比，而不是直接比总耗时。",
                        "from+size 的单次成本随 from 线性增长（每分片要吐 from+size 条候选）；search_after 每页只取 size 条，与页深无关。",
                        "search_after 必须配合稳定唯一排序（price + _shard_doc），否则会重复/漏数据。",
                        "search_after 不能跳页：它只能顺序往后翻，「直接跳到第 500 页」做不到，只能靠筛选条件缩小结果集。",
                        "60MB 的索引还不足以让 from+size 的成本增长到分钟级；数据量越大、from 越深，差距越明显。"));
    }

    /** 一次跳页：from=900 意味着每个分片都要取出前 900+100 条候选再归并 */
    private double byFromSize() {
        // @focus-start from+size：跳页越深，分片要吐出的候选越多
        JsonNode r = es.post("/" + V2 + "/_search", Json.map(
                "from", 900,
                "size", SIZE,
                "track_total_hits", false,
                "sort", Json.list(Json.map("price", "asc"), Json.map("sku", "asc")),
                "_source", Json.list("sku")));
        // @focus-end
        return r.path("took").asDouble();
    }

    /** 用 PIT + search_after 连续翻 10 页，PIT 保证搜索快照不被 refresh/merge 打乱 */
    private double bySearchAfter() throws Exception {
        JsonNode pit = es.post("/" + V2 + "/_pit?keep_alive=1m", null);
        String pitId = pit.path("id").asText();
        try {
            JsonNode after = null;
            long started = System.nanoTime();
            // @focus-start search_after：每页只取 size 条，用上一页最后一条的 sort 值续页
            for (int p = 0; p < 10; p++) {
                Map<String, Object> body = Json.map(
                        "size", SIZE,
                        "pit", Json.map("id", pitId, "keep_alive", "1m"),
                        "sort", Json.list(Json.map("price", "asc"), Json.map("_shard_doc", "asc")),
                        "_source", Json.list("sku"));
                if (after != null) body.put("search_after", after);
                JsonNode r = es.post("/_search", body);
                JsonNode hits = r.path("hits").path("hits");
                if (hits.size() < SIZE) break;
                after = hits.get(hits.size() - 1).path("sort");
            }
            // @focus-end
            return (System.nanoTime() - started) / 1_000_000.0;
        } finally {
            try {
                es.call("DELETE", "/_pit", Json.map("id", pitId));
            } catch (Exception ignored) {
                // PIT 最多 1 分钟自动过期，关不掉也不影响结论
            }
        }
    }

    /** TS 的模板字符串不会输出无意义的小数位，这里保持同样观感 */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
