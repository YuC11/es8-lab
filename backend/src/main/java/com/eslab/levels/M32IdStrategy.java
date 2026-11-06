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
 * M3-2 _id 的形态决定了写入选路
 *
 * ES 自动生成的 _id 是 20 字符 base64，写入时可以直接追加（append-only），
 * 业务自定义 _id 必须先查"这个 id 是否已存在"（去重），代价高一个量级。
 * 实测口径：_stats 里 index_time_in_millis 的差值 ÷ index_total 的差值。
 */
@Component
public class M32IdStrategy implements LevelModule {

    private static final String IDX_AUTO = "eslab-demo-id-auto";
    private static final String IDX_BIZ = "eslab-demo-id-biz";
    private static final int N = 3000;

    private static final Map<String, Object> DEF = Json.map(
            "settings", Json.map(
                    "number_of_shards", 1,
                    "number_of_replicas", 0,
                    "refresh_interval", "-1"),
            "mappings", Json.map(
                    "properties", Json.map(
                            "sku", Json.map("type", "keyword"),
                            "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                            "price", Json.map("type", "scaled_float", "scaling_factor", 100))));

    private record IndexStats(long timeMs, long total) {
    }

    private final EsClient es;
    private final ObjectMapper mapper;

    public M32IdStrategy(EsClient es, ObjectMapper mapper) {
        this.es = es;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "m3-2";
    }

    @Override
    public LevelResult run() throws Exception {
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            ProductDoc d = Generator.makeDoc(i, 42);
            docs.add(Json.map(
                    "sku", d.sku(),
                    "title", d.title(),
                    "price", d.price()));
        }

        // ── A：自动生成 _id ──
        // TS 版是单次墙钟测量；这里保持 1 轮正式采样，另加 1 轮预热（预热轮丢弃，不计入结果）。
        double[] autoWallMs = new double[1];
        long[] autoTimeDelta = new long[1];
        long[] autoDocsDelta = new long[1];

        Timing.Sample autoSample = Timing.sample(1, 1, round -> {
            deleteIndex(IDX_AUTO);
            es.put("/" + IDX_AUTO, DEF);
            IndexStats a0 = indexStats(IDX_AUTO);
            long ta = System.nanoTime();

            // @focus-start A 组：action 里不给 _id，ES 自动生成 20 字符 base64，写入走 append-only
            String ndAuto = buildNdjson(IDX_AUTO, docs, false);
            es.bulk(ndAuto + "\n", 120_000);
            // @focus-end

            double autoWall = (System.nanoTime() - ta) / 1_000_000.0;
            IndexStats a1 = indexStats(IDX_AUTO);
            if (round >= 0) {
                autoWallMs[0] = autoWall;
                autoTimeDelta[0] = a1.timeMs() - a0.timeMs();
                autoDocsDelta[0] = a1.total() - a0.total();
            }
            return (double) (a1.timeMs() - a0.timeMs()) / Math.max(1L, a1.total() - a0.total());
        });
        double autoMsPerDoc = autoSample.runs().get(0);

        // ── B：业务自定义 _id ──
        double[] bizWallMs = new double[1];
        long[] bizTimeDelta = new long[1];
        long[] bizDocsDelta = new long[1];

        Timing.Sample bizSample = Timing.sample(1, 1, round -> {
            deleteIndex(IDX_BIZ);
            es.put("/" + IDX_BIZ, DEF);
            IndexStats b0 = indexStats(IDX_BIZ);
            long tb = System.nanoTime();

            // @focus-start B 组：用 sku 当 _id，每次写入都要先在词典里查重
            String ndBiz = buildNdjson(IDX_BIZ, docs, true);
            es.bulk(ndBiz + "\n", 120_000);
            // @focus-end

            double bizWall = (System.nanoTime() - tb) / 1_000_000.0;
            IndexStats b1 = indexStats(IDX_BIZ);
            if (round >= 0) {
                bizWallMs[0] = bizWall;
                bizTimeDelta[0] = b1.timeMs() - b0.timeMs();
                bizDocsDelta[0] = b1.total() - b0.total();
            }
            return (double) (b1.timeMs() - b0.timeMs()) / Math.max(1L, b1.total() - b0.total());
        });
        double bizMsPerDoc = bizSample.runs().get(0);

        deleteIndex(IDX_AUTO);
        deleteIndex(IDX_BIZ);

        double ratio = bizMsPerDoc / Math.max(autoMsPerDoc, 0.0001);

        // 结论必须跟着实测走：本规模下若比值接近 1，就如实说"差异未显现"，而不是硬套"业务 id 更慢"
        String verdict = ratio >= 1.1
                ? "业务 _id 是自动 _id 的 " + String.format("%.1f", ratio) + " 倍，差距来自每次写入都要在词典里查重"
                : "两者基本持平（" + String.format("%.2f", ratio)
                + "×）——本规模下查重开销被其它成本掩盖了，\"业务 _id 一定更慢\"需要更大数据量才能验证";

        return LevelResult.builder("同样 " + N + " 条：自动 _id 每文档 "
                        + String.format("%.3f", autoMsPerDoc) + "ms，业务 _id 每文档 "
                        + String.format("%.3f", bizMsPerDoc) + "ms，" + verdict)
                .metrics(
                        new Metric("自动 _id", String.format("%.3f", autoMsPerDoc) + " ms/文档",
                                "总墙钟 " + Math.round(autoWallMs[0]) + "ms"),
                        new Metric("业务 _id", String.format("%.3f", bizMsPerDoc) + " ms/文档",
                                "总墙钟 " + Math.round(bizWallMs[0]) + "ms"),
                        new Metric("差距", String.format("%.1f", ratio) + "×", "数据量越大差距越明显"),
                        new Metric("样本量", N, null))
                .chart(BarChartData.of("ms/文档", null,
                        new BarChartData.Item("自动 _id（append-only）", Timing.round(autoMsPerDoc, 3)),
                        new BarChartData.Item("业务 _id（需查重）", Timing.round(bizMsPerDoc, 3))))
                .json(Json.map(
                        "auto", Json.map(
                                "wallMs", Math.round(autoWallMs[0]),
                                "indexTimeDelta", autoTimeDelta[0],
                                "docs", autoDocsDelta[0]),
                        "biz", Json.map(
                                "wallMs", Math.round(bizWallMs[0]),
                                "indexTimeDelta", bizTimeDelta[0],
                                "docs", bizDocsDelta[0])))
                .notes(
                        "口径说明：index_time_in_millis 是**墙钟时间**（含等待 IO 与 Lucene 内部锁），不是 CPU 时间；正因为含阻塞，它才适合衡量\"写一条要多久\"。",
                        "自动 id 的代价：放弃\"同一个 _id 覆盖更新\"的幂等能力。订单、商品这类需要按业务主键去重的场景，仍然应该用业务 _id。",
                        "折中方案：自动 _id + 业务主键做唯一约束（用一条独立的去重索引或外部存储），把查重从热路径挪走。",
                        "本关文档数固定 3000，比率会随数据规模变化；想验证\"规模放大后差距更大\"，可把 N 改大后重跑（代码在 m3-2.ts）。")
                .build();
    }

    private IndexStats indexStats(String index) {
        JsonNode s = es.get("/" + index + "/_stats/indexing");
        JsonNode t = s.path("indices").path(index).path("primaries").path("indexing");
        return new IndexStats(
                t.path("index_time_in_millis").asLong(),
                t.path("index_total").asLong());
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

    private void deleteIndex(String index) {
        try {
            es.delete("/" + index);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
