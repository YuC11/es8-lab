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

/**
 * M3-1 单条写入 vs 批量写入：差的是「请求开销」而不是「写入能力」
 *
 * 同样 200 条相同文档、同一个索引定义（refresh_interval=-1）、同样的字段：
 * 一条条 PUT 要 300 次往返与 300 次 translog 提交；_bulk 只要 1 次。
 *
 * 计量口径（性能结论只认这一套）：
 *   · 每侧 3 轮正式样本 + 1 轮预热，取中位数，并给出最小值与最大值看抖动；
 *   · 两侧都是「删索引 → 建索引 → 计时写入」，重建不计入耗时；
 *   · 写完核对条数与失败条目，两边必须都是 200 条、0 失败。
 */
@Component
public class M31BulkWrite implements LevelModule {

    private static final String INDEX = LabIndexes.DEMO_WRITE;
    private static final int N = 200;
    private static final int ROUNDS = 3;
    private static final int WARMUP = 1;

    private final EsClient es;
    private final LabIndexes indexes;
    private final ObjectMapper mapper;

    public M31BulkWrite(EsClient es, ObjectMapper mapper, LabIndexes indexes) {
        this.es = es;
        this.mapper = mapper;
        this.indexes = indexes;
    }

    @Override
    public String id() {
        return "m3-1";
    }

    @Override
    public LevelResult run() throws Exception {
        List<Map<String, Object>> slim = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            ProductDoc d = Generator.makeDoc(i, 42);
            slim.add(Json.map(
                    "sku", d.sku(),
                    "title", d.title(),
                    "price", d.price(),
                    "category", d.category(),
                    "tags", d.tags()));
        }

        // @focus-start 每条文档一次 HTTP 往返（这就是 N+1 的写入版）
        Timing.Sample single = Timing.sample(ROUNDS, WARMUP, round -> {
            recreate();
            long t1 = System.nanoTime();
            for (Map<String, Object> d : slim) {
                es.post("/" + INDEX + "/_doc", d);
            }
            return (System.nanoTime() - t1) / 1_000_000.0;
        });
        // @focus-end

        boolean[] bulkErrors = {false};
        int[] bulkItems = {0};
        int[] failedItems = {0};
        // @focus-start 一次请求带 N 条，NDJSON：action 行 + source 行交替
        Timing.Sample bulk = Timing.sample(ROUNDS, WARMUP, round -> {
            recreate();
            long t2 = System.nanoTime();
            JsonNode res = es.bulk(buildNdjson(slim) + "\n", 60_000);
            double ms = (System.nanoTime() - t2) / 1_000_000.0;
            if (round >= 0) {
                bulkErrors[0] = res.path("errors").asBoolean(false);
                bulkItems[0] = res.path("items").size();
                failedItems[0] = countFailures(res);
            }
            return ms;
        });
        // @focus-end

        es.post("/" + INDEX + "/_refresh", null);
        long count = es.get("/" + INDEX + "/_count").path("count").asLong();
        deleteIndex();

        double singleMedian = single.median();
        double bulkMedian = bulk.median();
        double speedup = bulkMedian > 0 ? singleMedian / bulkMedian : 0;

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("rounds", ROUNDS);
        json.put("warmup", WARMUP);
        json.put("docs", N);
        json.put("single", sample(single));
        json.put("bulk", sample(bulk));
        json.put("bulkErrors", bulkErrors[0]);
        json.put("bulkItems", bulkItems[0]);
        json.put("failedItems", failedItems[0]);
        json.put("countAfterBulk", count);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("单条 PUT", N + " 次", N + " 条",
                fmt(singleMedian) + " ms", fmt(singleMedian / N) + " ms/文档", range(single)));
        rows.add(TableData.row("_bulk", "1 次", bulkItems[0] + " 条（失败 " + failedItems[0] + "）",
                fmt(bulkMedian) + " ms", fmt(bulkMedian / N) + " ms/文档", range(bulk)));

        return LevelResult.builder("同样 " + N + " 条文档：单条提交中位数 " + fmt(singleMedian)
                        + "ms，_bulk 中位数 " + fmt(bulkMedian) + "ms，快 "
                        + String.format(Locale.ROOT, "%.1f", speedup)
                        + " 倍 —— 差距来自「每次请求一次 translog 提交 + 一次 HTTP 往返」，不是 ES 写得快")
                .metrics(
                        new Metric("单条写入（中位）", fmt(singleMedian) + " ms",
                                "请求数 " + N + " · " + ROUNDS + " 轮中位数"),
                        new Metric("_bulk（中位）", fmt(bulkMedian) + " ms",
                                "请求数 1 · " + ROUNDS + " 轮中位数"),
                        new Metric("提速倍数", String.format(Locale.ROOT, "%.1f", speedup) + "×",
                                "中位数相除，不是极端值"),
                        new Metric("写入条数", N + " / 校验 " + count + " 条", "两侧同一批数据"),
                        new Metric("失败条目", failedItems[0] + " 条", "读 items[].status，不看 HTTP 200"),
                        new Metric("样本区间（ms）", range(single) + " / " + range(bulk),
                                "单条组抖动更大"))
                .table(new TableData(List.of("方式", "请求数", "写入条数", "中位耗时", "每条代价",
                        "样本区间（ms）"), rows))
                .chart(BarChartData.of("ms",
                        "每侧 " + ROUNDS + " 轮 + " + WARMUP + " 轮预热，中位数；本机 localhost",
                        new BarChartData.Item("单条 " + N + " 次请求", Math.round(singleMedian)),
                        new BarChartData.Item("_bulk 1 次请求", Math.round(bulkMedian))))
                .json(json)
                .notes(
                        "关键机制：ES 8.x 的 index.translog.durability 默认是 request —— 每个写请求结束前都要 fsync 一次 translog。"
                                + N + " 次单条写入 = " + N + " 次 fsync；一次 _bulk = 1 次 fsync。"
                                + "本机单条中位数 " + fmt(singleMedian / N) + " ms/文档，绝大部分就是提交等待。",
                        "其次是 HTTP 往返：每条一次请求/响应编解码，跨机房时 RTT 会把这部分再放大一个量级，"
                                + "所以线上的倍数通常比本机更大，但方向一致。",
                        "口径边界：只有 " + ROUNDS + " 轮、本机单节点、refresh_interval=-1。"
                                + "样本区间 " + range(single) + " vs " + range(bulk)
                                + " 说明抖动不小，所以只报中位数与倍数，不报「最好成绩」；"
                                + "这个倍数不能直接外推到生产。",
                        "_bulk 的响应必须检查 `errors: true` 的条目：本关 " + bulkItems[0]
                                + " 条里失败 " + failedItems[0] + " 条（HTTP 200 不代表每条都成功）。",
                        "生产批量经验：单批 5–15MB（约 1000–5000 条小文档），过大反而会因为 GC 与线程池队列触发 429。")
                .build();
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

    private static Map<String, Object> sample(Timing.Sample s) {
        return Json.map("median", s.median(), "min", s.min(), "max", s.p95(),
                "runs", s.runs(), "rounds", s.rounds());
    }

    private static String range(Timing.Sample s) {
        return fmt(s.min()) + " – " + fmt(s.p95());
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
