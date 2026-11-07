package com.eslab.levels;

import com.eslab.data.DatasetService;
import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.Generator;
import com.eslab.seed.ProductDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * M0-4 把数据灌到 2 万条
 *
 * 前几关都是「一条一条」的手工操作，这一关换成真实做法：bulk 分批。
 * 三个数字会同时变化——写入速率、段数、体积——它们之间的拉扯就是后面 M3、M7 的主题。
 *
 * 本关的证据分三层，全部来自本轮真实响应：
 *   ① 每批进度：批号、累计条数、速率、失败条目数（读 items[].status，不看 HTTP 200）；
 *   ② 导入期的 refresh 设置：1s → -1 → 1s（finally 里恢复，异常路径也不漏），段数前后对照；
 *   ③ 批次大小对照：同样 1000 条，10 批 vs 1 批，比较请求数与耗时。
 * 主索引已达 2 万条时跳过对主索引的写入（幂等），但 ① ② ③ 仍会在一次性索引上真实跑一遍，
 * 所以每次运行都有数字可读，而不是「已达标，无内容」。
 */
@Component
public class M04BulkLoad implements LevelModule {

    private static final int TARGET = DatasetService.DEFAULT_TARGET;
    /** 演示用导入量：3 批 × 2000，够看出「每批」这件事，又不至于把这一关变成压测 */
    private static final int DEMO_DOCS = 6_000;
    private static final int DEMO_BATCH_SIZE = 2_000;
    /** 批次对照的样本量：太小测不出请求开销，太大就变成压测 */
    private static final int AB_DOCS = 1_000;

    private final EsClient es;
    private final DatasetService dataset;
    private final LabData lab;
    private final ObjectMapper mapper;

    public M04BulkLoad(EsClient es, DatasetService dataset, LabData lab, ObjectMapper mapper) {
        this.es = es;
        this.dataset = dataset;
        this.lab = lab;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "m0-4";
    }

    /** 一批写完的即时状态，页面直接画成进度表 */
    private record Progress(int batchIndex, int batches, int done, int total, int rate) {
    }

    private record BatchResult(int batchSize, int requests, int docs, int failures,
                               double ms, int rate,
                               String refreshBefore, String refreshDuring, String refreshAfter,
                               long segBefore, long segAfter) {
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(DatasetService.V1)) {
            throw new IllegalStateException("索引 " + DatasetService.V1 + " 还不存在，先完成 m0-2");
        }

        // ── 主索引：不足 2 万才灌，够了就跳过（重复执行必须幂等） ──
        long before = dataset.count(DatasetService.V1);
        String v1Refresh = refreshOf(DatasetService.V1);
        long v1SegBefore = lab.segmentCount(DatasetService.V1);

        List<DatasetService.BulkProgress> v1Samples = new ArrayList<>();
        String[] v1During = {"未导入"};
        DatasetService.BulkOutcome outcome = null;
        if (before < TARGET) {
            int remaining = (int) (TARGET - before);
            outcome = dataset.bulkLoad(DatasetService.V1, remaining, (int) before, p -> {
                v1Samples.add(p);
                // 导入中读一次 refresh，证明 -1 真的生效了，而不是只写在文案里
                if ("未导入".equals(v1During[0])) v1During[0] = refreshOf(DatasetService.V1);
            });
        }
        String v1RefreshAfter = refreshOf(DatasetService.V1);
        long v1SegAfter = lab.segmentCount(DatasetService.V1);
        long docsNow = dataset.count(DatasetService.V1);

        // ── 演示导入 + 批次对照：都写在一次性索引上，跑完即删 ──
        List<Progress> demoProgress = new ArrayList<>();
        BatchResult demo;
        BatchResult small;
        BatchResult large;
        try {
            lab.rebuild(LabIndexes.DEMO_BATCH, idx -> 0);
            demo = batchLoad(LabIndexes.DEMO_BATCH, DEMO_DOCS, DEMO_BATCH_SIZE, demoProgress::add);

            lab.rebuild(LabIndexes.DEMO_BATCH, idx -> 0);
            small = batchLoad(LabIndexes.DEMO_BATCH, AB_DOCS, 100, null);

            lab.rebuild(LabIndexes.DEMO_BATCH, idx -> 0);
            large = batchLoad(LabIndexes.DEMO_BATCH, AB_DOCS, 1_000, null);
        } finally {
            // 对照索引是一次性的：异常路径也删掉，否则会留在集群里变成垃圾
            if (lab.exists(LabIndexes.DEMO_BATCH)) lab.delete(LabIndexes.DEMO_BATCH);
        }

        List<List<Object>> rows = new ArrayList<>();
        for (DatasetService.BulkProgress p : v1Samples) {
            rows.add(TableData.row("v1 导入", "第 " + p.batchIndex() + "/" + p.batches() + " 批",
                    p.written() + " 条", p.rate() + " docs/s", "每批 2000 条 · refresh=-1"));
        }
        if (v1Samples.isEmpty()) {
            rows.add(TableData.row("v1 导入", "未写入", before + " 条", "—", "已达 2 万，跳过（幂等）"));
        }
        for (Progress p : demoProgress) {
            rows.add(TableData.row("演示导入", "第 " + p.batchIndex() + "/" + p.batches() + " 批",
                    p.done() + "/" + p.total() + " 条", p.rate() + " docs/s",
                    "refresh=" + demo.refreshDuring() + "（导入期）"));
        }
        rows.add(TableData.row("批量对照", "每批 100 条", small.docs() + " 条", small.rate() + " docs/s",
                small.requests() + " 次请求 · " + fmt(small.ms()) + " ms · 失败 " + small.failures()
                        + " · 段 " + small.segBefore() + "→" + small.segAfter()));
        rows.add(TableData.row("批量对照", "每批 1000 条", large.docs() + " 条", large.rate() + " docs/s",
                large.requests() + " 次请求 · " + fmt(large.ms()) + " ms · 失败 " + large.failures()
                        + " · 段 " + large.segBefore() + "→" + large.segAfter()));

        String summary = (outcome == null
                ? "索引里已有 " + docsNow + " 条，达到 2 万的目标，本次跳过对 v1 的写入（幂等）；"
                : "2 万条写完了：" + String.format(Locale.ROOT, "%.1f", outcome.ms() / 1000.0) + " 秒，平均 "
                + outcome.rate() + " docs/s；")
                + "演示导入 " + DEMO_DOCS + " 条按每批 " + DEMO_BATCH_SIZE + " 条分 " + demo.requests()
                + " 批跑完（" + fmt(demo.ms()) + " ms），同样 " + AB_DOCS + " 条改成每批 100 条要 "
                + small.requests() + " 次请求（" + fmt(small.ms()) + " ms），每批 1000 条只要 "
                + large.requests() + " 次（" + fmt(large.ms()) + " ms）";

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v1", outcome == null
                ? Json.map("skipped", true, "docs", docsNow)
                : Json.map("written", outcome.written(), "ms", outcome.ms(), "rate", outcome.rate(),
                "batches", outcome.batches(), "docs", outcome.docs(),
                "segments", outcome.segments(), "storeBytes", outcome.storeBytes()));
        json.put("v1Refresh", Json.map("before", v1Refresh, "during", v1During[0], "after", v1RefreshAfter));
        json.put("v1Segments", Json.map("before", v1SegBefore, "after", v1SegAfter));
        json.put("demo", Json.map("docs", demo.docs(), "requests", demo.requests(),
                "ms", demo.ms(), "failures", demo.failures(),
                "refresh", Json.map("before", demo.refreshBefore(), "during", demo.refreshDuring(),
                        "after", demo.refreshAfter()),
                "segments", Json.map("before", demo.segBefore(), "after", demo.segAfter())));
        json.put("ab", Json.map(
                "small", Json.map("batchSize", small.batchSize(), "requests", small.requests(),
                        "ms", small.ms(), "failures", small.failures()),
                "large", Json.map("batchSize", large.batchSize(), "requests", large.requests(),
                        "ms", large.ms(), "failures", large.failures())));

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("本次写入 v1", (outcome == null ? 0 : outcome.written()) + " 条",
                                outcome == null ? "v1 已达标，跳过" : "起点 " + before + " 条"),
                        new Metric("总文档数", docsNow, "以 _count 为准"),
                        new Metric("演示导入速率", demo.rate() + " docs/s",
                                demo.docs() + " 条 / " + demo.requests() + " 批"),
                        new Metric("段数（演示索引）", demo.segBefore() + " → " + demo.segAfter(),
                                "refresh 才落段，后台 merge 会再合并"),
                        new Metric("refresh_interval", demo.refreshBefore() + " → " + demo.refreshDuring()
                                + " → " + demo.refreshAfter(), "finally 里恢复，异常路径也恢复"),
                        new Metric("失败条目", (demo.failures() + small.failures() + large.failures()) + " 条",
                                "读 items[].status，不看 HTTP 200"))
                .table(new TableData(List.of("阶段", "批次 / 参数", "累计条数", "速率", "备注"), rows))
                .json(json)
                .notes(
                        "导入期间把 refresh_interval 设成 -1，导完再恢复 1s：每次 refresh 都要生成新段并可能触发 merge，"
                                + "这是批量导入最大的隐性开销。演示导入的实测变化是 " + demo.refreshBefore()
                                + " → " + demo.refreshDuring() + " → " + demo.refreshAfter() + "。",
                        "段数不是「写多少条就多少个段」：一次 bulk 的文档先进内存缓冲，refresh 才落成段，"
                                + "后台 merge 再把小段合成大段。本次 " + demo.requests() + " 批写 "
                                + demo.docs() + " 条，段数 " + demo.segBefore() + " → " + demo.segAfter()
                                + "；批次对照两组也都是 " + small.segBefore() + "→" + small.segAfter()
                                + "，段数只能看趋势，不能当写入条数的计数器。",
                        "批次大小对照的结论只到「每批 1000 条比每批 100 条少 9 次请求」这一层："
                                + "样本只有 " + AB_DOCS + " 条、单轮、本机 localhost。批次再往上加会遇到单请求体积与 GC 的上限，"
                                + "生产常用 5–15MB 一批，而不是无限大。",
                        "每条文档的 _id 是自动生成的（bulk 只给了 _index）。m3-2 会对比「自定义 _id」与「自动 _id」的写入选路。",
                        "单节点 0 副本：如果开 1 副本，写入量翻倍且分片无法分配，集群会卡在 yellow。",
                        "重复运行只会补齐到 2 万条：本次 v1 起点 " + before + " 条，写入 "
                                + (outcome == null ? 0 : outcome.written()) + " 条；演示索引每轮重建后删除，不留残留。")

                .build();
    }

    /**
     * 按指定批次大小灌数据，返回真实请求数、失败条目、段数与刷新设置。
     *
     * 与 DatasetService.bulkLoad 的差别只有批次大小可传。其余（导入期关刷新、finally 恢复、
     * 导完 refresh）保持一致，否则对照的两组就不止差一个变量了。
     */
    private BatchResult batchLoad(String index, int docs, int batchSize, Consumer<Progress> listener)
            throws Exception {
        long segBefore = lab.segmentCount(index);
        String refreshBefore = refreshOf(index);
        es.put("/" + index + "/_settings", Json.map("index", Json.map("refresh_interval", "-1")));
        String refreshDuring = refreshOf(index);
        int done = 0;
        int requests = 0;
        int failures = 0;
        int batches = (int) Math.ceil(docs / (double) batchSize);
        long started = System.nanoTime();
        try {
            while (done < docs) {
                int end = Math.min(done + batchSize, docs);
                StringBuilder sb = new StringBuilder((end - done) * 500);
                for (int i = done; i < end; i++) {
                    ProductDoc d = Generator.makeDoc(i, 42);
                    sb.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
                    sb.append(mapper.writeValueAsString(Json.map(
                            "sku", d.sku(), "title", d.title(), "price", d.price(),
                            "category", d.category(), "tags", d.tags()))).append('\n');
                }
                JsonNode res = es.bulk(sb.toString(), 120_000);
                requests++;
                failures += countFailures(res);
                done = end;
                if (listener != null) {
                    double ms = Math.max((System.nanoTime() - started) / 1_000_000.0, 0.001);
                    listener.accept(new Progress(requests, batches, done, docs,
                            (int) (done / (ms / 1000.0))));
                }
            }
        } finally {
            es.put("/" + index + "/_settings", Json.map("index", Json.map("refresh_interval", "1s")));
            es.post("/" + index + "/_refresh", null);
        }
        double ms = (System.nanoTime() - started) / 1_000_000.0;
        int rate = (int) (docs / Math.max(ms / 1000.0, 0.001));
        return new BatchResult(batchSize, requests, docs, failures, ms, rate,
                refreshBefore, refreshDuring, refreshOf(index), segBefore, lab.segmentCount(index));
    }

    /** HTTP 200 不代表每条都成功，必须逐项读 items[].status */
    private static int countFailures(JsonNode res) {
        int n = 0;
        for (JsonNode item : res.path("items")) {
            java.util.Iterator<JsonNode> it = item.elements();
            if (!it.hasNext()) continue;
            JsonNode first = it.next();
            if (first.has("error") || first.path("status").asInt(200) >= 300) n++;
        }
        return n;
    }

    /** 读索引当前的 refresh_interval；没显式设置时回到默认 1s */
    private String refreshOf(String index) {
        try {
            JsonNode s = es.get("/" + index + "/_settings").path(index)
                    .path("settings").path("index");
            String v = s.path("refresh_interval").asText("");
            return v.isEmpty() ? "1s（默认）" : v;
        } catch (EsException e) {
            return "?";
        }
    }

    private static String fmt(double ms) {
        return String.format(Locale.ROOT, "%.0f", ms);
    }
}
