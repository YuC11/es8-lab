package com.eslab.levels;

import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M3-3 近实时：写入后为什么"立刻查不到"
 *
 * ES 写入先进内存 buffer + translog，refresh 才生成可搜索的段。
 * 默认 refresh_interval=1s，所以写入到可搜索之间存在一个可见性窗口。
 */
@Component
public class M33NearRealTime implements LevelModule {

    private static final String INDEX = LabIndexes.DEMO_REFRESH;

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

    @Override
    public LevelResult run() throws Exception {
        deleteIndex();
        es.put("/" + INDEX, indexes.definitionOf(INDEX));

        // @focus-start 写入后立刻查：refresh=false 时不保证可见
        es.post("/" + INDEX + "/_doc", Json.map(
                "sku", "RT-1",
                "title", "近实时测试 商品一"));
        long immediately = search("RT-1");
        long waited = System.currentTimeMillis();
        long visibleAfterMs = -1;
        while (System.currentTimeMillis() - waited < 3000) {
            if (search("RT-1") > 0) {
                visibleAfterMs = System.currentTimeMillis() - waited;
                break;
            }
            Thread.sleep(25);
        }
        // @focus-end

        // @focus-start 需要"写完就能查"时：refresh=wait_for 让本次请求等到刷新完成
        long t0 = System.nanoTime();
        es.post("/" + INDEX + "/_doc?refresh=wait_for", Json.map(
                "sku", "RT-2",
                "title", "近实时测试 商品二"));
        double waitForMs = (System.nanoTime() - t0) / 1_000_000.0;
        long afterWaitFor = search("RT-2");
        // @focus-end

        // 代价对照：批量导入时每批都 wait_for，吞吐会掉多少（用 200 条小样本演示）
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            docs.add(Json.map(
                    "sku", "PERF-" + i,
                    "title", "刷新代价样本 " + i));
        }
        String payload = buildNdjson(INDEX, docs);

        long tNoRefresh = System.nanoTime();
        es.call("POST", "/_bulk?refresh=false", null, payload + "\n", 60_000);
        double noRefreshMs = (System.nanoTime() - tNoRefresh) / 1_000_000.0;

        deleteIndex();
        es.put("/" + INDEX, Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0),
                "mappings", Json.map(
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text")))));
        long tWithRefresh = System.nanoTime();
        es.call("POST", "/_bulk?refresh=wait_for", null, payload + "\n", 60_000);
        double withRefreshMs = (System.nanoTime() - tWithRefresh) / 1_000_000.0;

        deleteIndex();

        String visible = visibleAfterMs >= 0 ? visibleAfterMs + "ms" : ">3000ms";

        return LevelResult.builder("写入后立即查询返回 " + immediately + " 条，约 " + visible
                        + " 后才可见；改用 refresh=wait_for 后本次请求耗时 "
                        + String.format("%.0f", waitForMs) + "ms 但立刻可查")
                .metrics(
                        new Metric("写入后立即查询", immediately + " 条", "预期 0 条"),
                        new Metric("实际可见延迟", visibleAfterMs >= 0 ? visibleAfterMs + " ms" : ">3000 ms", null),
                        new Metric("wait_for 请求耗时", String.format("%.0f", waitForMs) + " ms", null),
                        new Metric("200 条批量耗时",
                                String.format("%.0f", noRefreshMs) + " → " + String.format("%.0f", withRefreshMs) + " ms",
                                "refresh=false → wait_for"))
                .chart(BarChartData.of("ms", "同一批 200 条文档，只改 refresh 策略",
                        new BarChartData.Item("批量 refresh=false", (double) Math.round(noRefreshMs)),
                        new BarChartData.Item("批量 refresh=wait_for", (double) Math.round(withRefreshMs))))
                .notes(
                        "所以\"写完立刻查\"的需求不能靠 ES 保证；要么业务上容忍 1s 延迟，要么对单次写请求加 refresh=wait_for（只用于低频关键写入）。",
                        "批量导入时绝对不能每条都 wait_for：每次 refresh 都要生成新段并可能触发 merge，吞吐会掉一个数量级。",
                        "translog 保证的是\"宕机不丢\"，不是\"立即可查\"；这两个概念经常被混为一谈。",
                        "本关索引已删除，不影响主索引。")
                .build();
    }

    private long search(String sku) {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0,
                "query", Json.map("term", Json.map("sku", sku))));
        return r.path("hits").path("total").path("value").asLong();
    }

    private String buildNdjson(String index, List<Map<String, Object>> docs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> d : docs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(mapper.writeValueAsString(Json.map("index", Json.map("_index", index))))
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
