package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.seed.Generator;
import com.eslab.seed.ProductDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M3-1 单条写入 vs 批量写入：差的是"请求开销"而不是"写入能力"
 *
 * 500 条相同文档，一条条 PUT 要 500 次往返；_bulk 只要 1 次。
 * 这个差距在跨网络（哪怕只是 localhost 的 HTTP 栈）时会被放大。
 */
@Component
public class M31BulkWrite implements LevelModule {

    private static final String INDEX = "eslab-demo-write";
    private static final int N = 300;

    private static final Map<String, Object> DEF = Json.map(
            "settings", Json.map(
                    "number_of_shards", 1,
                    "number_of_replicas", 0,
                    "refresh_interval", "-1"),
            "mappings", Json.map(
                    "properties", Json.map(
                            "sku", Json.map("type", "keyword"),
                            "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                            "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                            "category", Json.map("type", "keyword"),
                            "tags", Json.map("type", "keyword"))));

    private final EsClient es;
    private final ObjectMapper mapper;

    public M31BulkWrite(EsClient es, ObjectMapper mapper) {
        this.es = es;
        this.mapper = mapper;
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

        // ── 单条写入 ──
        // TS 版是单次墙钟测量；这里保持 1 轮正式采样，另加 1 轮预热（预热轮丢弃，不计入结果）。
        Timing.Sample singleSample = Timing.sample(1, 1, round -> {
            deleteIndex();
            es.put("/" + INDEX, DEF);
            long t1 = System.nanoTime();

            // @focus-start 每条文档一次 HTTP 往返（这就是 N+1 的写入版）
            for (Map<String, Object> d : slim) {
                es.post("/" + INDEX + "/_doc", d);
            }
            // @focus-end

            return (System.nanoTime() - t1) / 1_000_000.0;
        });
        double singleMs = singleSample.runs().get(0);

        // ── 批量写入 ──
        boolean[] bulkErrors = new boolean[1];
        int[] bulkItems = new int[1];

        Timing.Sample bulkSample = Timing.sample(1, 1, round -> {
            deleteIndex();
            es.put("/" + INDEX, DEF);
            long t2 = System.nanoTime();

            // @focus-start 一次请求带 N 条，NDJSON：action 行 + source 行交替
            String payload = buildNdjson(INDEX, slim, false);
            JsonNode bulkRes = es.bulk(payload + "\n", 60_000);
            // @focus-end

            double ms = (System.nanoTime() - t2) / 1_000_000.0;
            if (round >= 0) {
                bulkErrors[0] = bulkRes.path("errors").asBoolean();
                bulkItems[0] = bulkRes.path("items").size();
            }
            return ms;
        });
        double bulkMs = bulkSample.runs().get(0);

        es.get("/" + INDEX + "/_refresh");
        long count = es.get("/" + INDEX + "/_count").path("count").asLong();
        deleteIndex();

        return LevelResult.builder("同样 " + N + " 条文档：单条提交 " + Math.round(singleMs)
                        + "ms，_bulk 提交 " + Math.round(bulkMs) + "ms，快 "
                        + String.format("%.1f", singleMs / bulkMs)
                        + " 倍——差距来自「每个请求一次 translog fsync + 一次 HTTP 往返」，不是 ES 写得快")
                .metrics(
                        new Metric("单条写入", Math.round(singleMs) + " ms",
                                String.format("%.2f", singleMs / N) + " ms/文档"),
                        new Metric("_bulk 写入", Math.round(bulkMs) + " ms",
                                String.format("%.2f", bulkMs / N) + " ms/文档"),
                        new Metric("提速倍数", String.format("%.1f", singleMs / bulkMs) + "×", null),
                        new Metric("写入条数", count, null))
                .chart(BarChartData.of("ms",
                        "本机 localhost 下已是这个倍数；跨机房 RTT 3ms 时差距会再放大一个量级",
                        new BarChartData.Item("单条 " + N + " 次请求", (double) Math.round(singleMs)),
                        new BarChartData.Item("_bulk 1 次请求", (double) Math.round(bulkMs))))
                .json(Json.map("bulkErrors", bulkErrors[0], "items", bulkItems[0]))
                .notes(
                        "关键机制：ES 8.x 的 index.translog.durability 默认是 request —— 每个写请求结束前都要 fsync 一次 translog。"
                                + N + " 次单条写入 = " + N + " 次 fsync；一次 _bulk = 1 次 fsync。本机实测单条约 "
                                + String.format("%.1f", singleMs / N)
                                + "ms，其中绝大部分就是 fsync 的等待时间。",
                        "其次才是 HTTP 往返：每条一次连接复用后的请求/响应编解码，跨机房时 RTT 会把这部分进一步放大。",
                        "生产批量经验：单批 5–15MB（约 1000–5000 条小文档），过大反而会因为 GC 与线程池队列触发 429。",
                        "_bulk 的响应里必须检查 `errors: true` 的条目：批量整体返回 200 不代表每条都成功。",
                        "本关索引导入期间设了 refresh_interval=-1，避免每 1s 的 refresh 干扰纯写入耗时测量。")
                .build();
    }

    private String buildNdjson(String index, List<Map<String, Object>> docs, boolean withId) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> d : docs) {
            if (sb.length() > 0) sb.append('\n');
            Map<String, Object> action = withId
                    ? Json.map("index", Json.map("_index", index, "_id", d.get("sku")))
                    : Json.map("index", Json.map("_index", index));
            sb.append(mapper.writeValueAsString(action))
                    .append('\n')
                    .append(mapper.writeValueAsString(d));
        }
        return sb.toString();
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
