package com.eslab.levels;

import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M3-3 近实时：写入后为什么「立刻查不到」
 *
 * ES 写入先进内存 buffer + translog，refresh 才生成可搜索的段。
 * 默认 refresh_interval=1s，所以「写入成功」与「可以查到」是两个时间点。
 *
 * 三条路径各自记录「写入响应 / 立即查询 / 可见延迟 / 请求耗时」：
 *   ① 默认（refresh=false）：写入立刻返回，但数据要等下一次刷新才可见；
 *   ② refresh=wait_for：本次请求等到刷新完成再返回，写完即可查，代价是请求被拖长；
 *   ③ refresh=true：强制刷新并返回，同样可查，代价是每次写都造一个新段。
 * 再用 200 条 bulk 采样三种策略的中位数，说明「为什么批量导入不能每条都刷新」。
 */
@Component
public class M33NearRealTime implements LevelModule {

    private static final String INDEX = LabIndexes.DEMO_REFRESH;
    private static final int BULK_DOCS = 200;
    private static final int ROUNDS = 3;
    private static final int WARMUP = 1;
    private static final int POLL_LIMIT_MS = 3_000;

    private final EsClient es;
    private final LabIndexes indexes;
    private final ObjectMapper mapper;

    public M33NearRealTime(EsClient es, ObjectMapper mapper, LabIndexes indexes) {
        this.es = es;
        this.mapper = mapper;
        this.indexes = indexes;
    }

    @Override
    public String id() {
        return "m3-3";
    }

    private record WritePath(String name, String result, long version, double requestMs,
                             long immediately, long visibleAfterMs) {
    }

    @Override
    public LevelResult run() throws Exception {
        WritePath plain = null;
        WritePath waitFor = null;
        WritePath force = null;
        Timing.Sample noRefresh = null;
        Timing.Sample waitForBulk = null;
        Timing.Sample forceBulk = null;
        long segmentsAfterBulk = 0;
        try {
            recreate();

            // @focus-start ① 默认：写入立刻返回，但不保证可见；轮询到它真的能查出来为止
            long t0 = System.nanoTime();
            JsonNode r1 = es.post("/" + INDEX + "/_doc", Json.map(
                    "sku", "RT-1", "title", "近实时测试 商品一"));
            double ms1 = (System.nanoTime() - t0) / 1_000_000.0;
            long immediately1 = countSku("RT-1");
            long visible1 = pollVisible("RT-1");
            plain = new WritePath("默认（refresh=false）", r1.path("result").asText("?"),
                    r1.path("_version").asLong(), ms1, immediately1, visible1);

            // ② refresh=wait_for：本次请求等到下一次刷新完成
            long t1 = System.nanoTime();
            JsonNode r2 = es.post("/" + INDEX + "/_doc?refresh=wait_for", Json.map(
                    "sku", "RT-2", "title", "近实时测试 商品二"));
            double ms2 = (System.nanoTime() - t1) / 1_000_000.0;
            long immediately2 = countSku("RT-2");
            waitFor = new WritePath("refresh=wait_for", r2.path("result").asText("?"),
                    r2.path("_version").asLong(), ms2, immediately2, immediately2 > 0 ? 0 : -1);

            // ③ refresh=true：立即刷新，代价是每次写都生成新段
            long t2 = System.nanoTime();
            JsonNode r3 = es.post("/" + INDEX + "/_doc?refresh=true", Json.map(
                    "sku", "RT-3", "title", "近实时测试 商品三"));
            double ms3 = (System.nanoTime() - t2) / 1_000_000.0;
            long immediately3 = countSku("RT-3");
            force = new WritePath("refresh=true", r3.path("result").asText("?"),
                    r3.path("_version").asLong(), ms3, immediately3, immediately3 > 0 ? 0 : -1);
            // @focus-end

            // @focus-start 代价对照：200 条 bulk 在三种刷新策略下的中位数
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < BULK_DOCS; i++) {
                docs.add(Json.map("sku", "PERF-" + i, "title", "刷新代价样本 " + i));
            }
            String payload = buildNdjson(docs) + "\n";

            noRefresh = Timing.sample(ROUNDS, WARMUP, round -> {
                recreate();
                long s = System.nanoTime();
                es.call("POST", "/_bulk?refresh=false", null, payload, 60_000);
                return (System.nanoTime() - s) / 1_000_000.0;
            });
            waitForBulk = Timing.sample(ROUNDS, WARMUP, round -> {
                recreate();
                long s = System.nanoTime();
                es.call("POST", "/_bulk?refresh=wait_for", null, payload, 60_000);
                return (System.nanoTime() - s) / 1_000_000.0;
            });
            forceBulk = Timing.sample(ROUNDS, WARMUP, round -> {
                recreate();
                long s = System.nanoTime();
                es.call("POST", "/_bulk?refresh=true", null, payload, 60_000);
                return (System.nanoTime() - s) / 1_000_000.0;
            });
            // @focus-end
            segmentsAfterBulk = segmentCount();
        } finally {
            // 固定 _id 的样例文档与对照索引一起删掉，不留残留
            deleteIndex();
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(pathRow(plain));
        rows.add(pathRow(waitFor));
        rows.add(pathRow(force));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("paths", List.of(
                pathJson(plain), pathJson(waitFor), pathJson(force)));
        json.put("bulk", Json.map(
                "docs", BULK_DOCS,
                "refreshFalse", sample(noRefresh),
                "refreshWaitFor", sample(waitForBulk),
                "refreshTrue", sample(forceBulk)));

        double waitForExtra = waitFor.requestMs() - plain.requestMs();
        double forceExtra = force.requestMs() - plain.requestMs();
        String summary = "写入立刻返回（" + fmt(plain.requestMs()) + "ms）但查询返回 " + plain.immediately()
                + " 条，约 " + (plain.visibleAfterMs() >= 0 ? plain.visibleAfterMs() + "ms" : ">3000ms")
                + " 后才可见；加 refresh=wait_for 后本次请求变成 " + fmt(waitFor.requestMs())
                + "ms（多花 " + fmt(waitForExtra) + "ms）却立刻可查；"
                + "200 条 bulk 的代价是 " + fmt(noRefresh.median()) + " → "
                + fmt(waitForBulk.median()) + " → " + fmt(forceBulk.median()) + "ms";

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("默认：立即查询", plain.immediately() + " 条", "写入已成功，但还不可搜索"),
                        new Metric("默认：可见延迟", plain.visibleAfterMs() >= 0
                                ? plain.visibleAfterMs() + " ms" : ">3000 ms", "轮询到能查出来的耗时"),
                        new Metric("wait_for 写入耗时", fmt(waitFor.requestMs()) + " ms",
                                "比默认多 " + fmt(waitForExtra) + " ms"),
                        new Metric("refresh=true 写入耗时", fmt(force.requestMs()) + " ms",
                                "比默认多 " + fmt(forceExtra) + " ms"),
                        new Metric("200 条 bulk（中位）",
                                fmt(noRefresh.median()) + " / " + fmt(waitForBulk.median())
                                        + " / " + fmt(forceBulk.median()) + " ms",
                                "refresh=false / wait_for / true"),
                        new Metric("段数（200 条后）", String.valueOf(segmentsAfterBulk),
                                "每次 refresh 都造一个新段，之后靠 merge 合并"))
                .table(new TableData(List.of("写入路径", "写入响应", "立即查询", "可见延迟", "请求耗时"),
                        rows))
                .chart(BarChartData.of("ms",
                        "同一批 200 条，每侧 " + ROUNDS + " 轮 + " + WARMUP + " 轮预热，取中位数",
                        new BarChartData.Item("refresh=false", Math.round(noRefresh.median())),
                        new BarChartData.Item("refresh=wait_for", Math.round(waitForBulk.median())),
                        new BarChartData.Item("refresh=true", Math.round(forceBulk.median()))))
                .json(json)
                .notes(
                        "translog 保证的是「宕机不丢」，不是「立即可查」：写入返回 201 只说明进了 buffer + translog，"
                                + "段还没生成。默认 refresh_interval=1s，所以可见延迟通常落在 0–1000ms。",
                        "refresh=wait_for 只是把「等刷新」挪进本次请求：它返回时数据确实可查，"
                                + "但请求耗时从 " + fmt(plain.requestMs()) + "ms 涨到 " + fmt(waitFor.requestMs())
                                + "ms。它不会让刷新变快，只是让调用方知道什么时候能查到。",
                        "refresh=true 每次写都强制刷新：单条只多 " + fmt(forceExtra) + "ms，200 条 bulk 从 "
                                + fmt(noRefresh.median()) + "ms 涨到 " + fmt(forceBulk.median())
                                + "ms —— 本机单分片下涨幅有限，因为它买到的「立即可查」只有一次，"
                                + "真正的代价是段数：每刷新一次就落一个新段，之后靠后台 merge 收回去，"
                                + "批量导入时这笔账会放大到可观测的量级（实验 D 里有 2 万条的对照）。",
                        "所以「写完立刻查」的需求不能靠 ES 保证：要么业务容忍 1s 延迟（推荐），"
                                + "要么对低频的关键写入用 wait_for，要么写完直接返回自己刚写的那条数据（读己之写）。",
                        "本关索引与固定 _id 的样例文档已在 finally 里删除；重跑得到的是新一轮的数字。")
                .build();
    }

    private static List<Object> pathRow(WritePath p) {
        return TableData.row(p.name(), p.result() + " / _version " + p.version(),
                p.immediately() + " 条",
                p.visibleAfterMs() >= 0 ? p.visibleAfterMs() + " ms" : ">3000 ms",
                fmt(p.requestMs()) + " ms");
    }

    private static Map<String, Object> pathJson(WritePath p) {
        return Json.map("name", p.name(), "result", p.result(), "version", p.version(),
                "requestMs", p.requestMs(), "immediately", p.immediately(),
                "visibleAfterMs", p.visibleAfterMs());
    }

    private long countSku(String sku) {
        return es.post("/" + INDEX + "/_search", Json.map(
                "size", 0, "track_total_hits", true,
                "query", Json.map("term", Json.map("sku", sku))))
                .path("hits").path("total").path("value").asLong();
    }

    /** 轮询到「这条文档能被搜到」为止，返回耗时毫秒；超时返回 -1 */
    private long pollVisible(String sku) throws InterruptedException {
        long started = System.currentTimeMillis();
        while (System.currentTimeMillis() - started < POLL_LIMIT_MS) {
            if (countSku(sku) > 0) return System.currentTimeMillis() - started;
            Thread.sleep(20);
        }
        return -1;
    }

    private long segmentCount() {
        try {
            JsonNode r = es.get("/" + INDEX + "/_stats/segments");
            return r.path("_all").path("primaries").path("segments").path("count").asLong(0);
        } catch (EsException e) {
            return 0;
        }
    }

    private void recreate() {
        deleteIndex();
        es.put("/" + INDEX, indexes.definitionOf(INDEX));
    }

    private String buildNdjson(List<Map<String, Object>> docs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> d : docs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(mapper.writeValueAsString(Json.map("index", Json.map("_index", INDEX))))
                    .append('\n')
                    .append(mapper.writeValueAsString(d));
        }
        return sb.toString();
    }

    private static Map<String, Object> sample(Timing.Sample s) {
        return Json.map("median", s.median(), "min", s.min(), "max", s.p95(),
                "runs", s.runs(), "rounds", s.rounds());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
