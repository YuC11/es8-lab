package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * m11-3：ingest pipeline。
 *
 * 字段改名、补算、脱敏如果在每个写入方各写一遍，迟早会漏。
 * pipeline 把「入库前的加工」收成一份可测试的定义 —— _simulate 就是它的单元测试。
 */
@Component
public class M113IngestPipeline implements LevelModule {

    private static final String PIPELINE = "eslab-goods-enrich";
    private static final String INDEX = "eslab-goods-ingest";
    private static final String DOC_ID = "INGEST-1";

    private final EsClient es;
    private final LabData lab;

    public M113IngestPipeline(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m11-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 五个处理器：补时间戳、改名、清洗空白、算含税价、删内部字段
        Map<String, Object> pipeline = Json.map(
                "description", "商品入库前的清洗与补算",
                "processors", List.of(
                        Json.map("set", Json.map(
                                "field", "ingested_at",
                                "value", "{{{_ingest.timestamp}}}")),
                        Json.map("rename", Json.map(
                                "field", "old_sku",
                                "target_field", "sku")),
                        Json.map("gsub", Json.map(
                                "field", "title",
                                "pattern", "\\s+",
                                "replacement", " ")),
                        Json.map("script", Json.map(
                                "source", "if (ctx.price != null) {"
                                        + " ctx.price_with_tax = Math.round(ctx.price * 1.13 * 100) / 100.0; }")),
                        Json.map("remove", Json.map("field", "internal_note"))));
        es.put("/_ingest/pipeline/" + PIPELINE, pipeline);
        // @focus-end

        // @focus-start 先模拟：不改任何文档，直接看处理后的 _source
        JsonNode inline = es.post("/_ingest/pipeline/_simulate", Json.map(
                "pipeline", pipeline,
                "docs", List.of(Json.map("_source", source()))));
        // @focus-end

        JsonNode stored = es.post("/_ingest/pipeline/" + PIPELINE + "/_simulate", Json.map(
                "docs", List.of(Json.map("_source", source()))));

        if (!lab.exists(INDEX)) {
            es.put("/" + INDEX, Json.map(
                    "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                    "mappings", Json.map(
                            "dynamic", true,
                            "properties", Json.map(
                                    "sku", Json.map("type", "keyword"),
                                    "title", Json.map("type", "text",
                                            "analyzer", "ik_max_word", "search_analyzer", "ik_smart"),
                                    "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                    "price_with_tax", Json.map("type", "scaled_float", "scaling_factor", 100),
                                    "status", Json.map("type", "integer"),
                                    "ingested_at", Json.map("type", "date")))));
        }

        // @focus-start 写入时带上 ?pipeline=，处理器在索引之前执行
        JsonNode write = es.put("/" + INDEX + "/_doc/" + DOC_ID
                + "?pipeline=" + PIPELINE + "&refresh=true", source());
        // @focus-end

        JsonNode got = es.get("/" + INDEX + "/_doc/" + DOC_ID);
        JsonNode after = got.path("_source");
        JsonNode simulated = stored.path("docs").path(0).path("doc").path("_source");

        Object tax = after.has("price_with_tax") ? after.get("price_with_tax").asDouble() : null;
        boolean hasOldSku = after.has("old_sku");
        boolean hasNote = after.has("internal_note");
        String title = after.path("title").asText("");
        String ingestedAt = after.path("ingested_at").asText("");

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("old_sku → sku", "old_sku", hasOldSku ? "还在（异常）" : "sku=" + after.path("sku").asText("")));
        rows.add(TableData.row("title 空白", "「  蓝牙   耳机  主动降噪 」", "「" + title + "」"));
        rows.add(TableData.row("price", "899", after.path("price").asText("")));
        rows.add(TableData.row("price_with_tax", "（不存在）", tax == null ? "未生成" : String.valueOf(tax)));
        rows.add(TableData.row("internal_note", "有", hasNote ? "还在（异常）" : "已移除"));
        rows.add(TableData.row("ingested_at", "（不存在）", ingestedAt.isEmpty() ? "未写入" : ingestedAt));
        rows.add(TableData.row("模拟与落库一致", "—",
                simulated.path("price_with_tax").asText("").equals(after.path("price_with_tax").asText(""))
                        ? "一致" : "不一致，检查处理器顺序"));

        return LevelResult.builder("一条「脏」文档经过 5 个处理器后入库：改名、清空白、补含税价、删内部字段，"
                        + "含税价 " + (tax == null ? "未生成" : tax) + " 来自 price×1.13")
                .metrics(
                        new Metric("处理器数", 5, "set / rename / gsub / script / remove"),
                        new Metric("price", after.path("price").asText("—"), "原样保留"),
                        new Metric("price_with_tax", tax == null ? "—" : tax, "899 × 1.13"),
                        new Metric("ingested_at", ingestedAt.isEmpty() ? "—" : "已写入", "来自 _ingest.timestamp"),
                        new Metric("写入结果", write.path("result").asText("?"), "经 pipeline 处理后落库"))
                .table(TableData.of(List.of("字段", "处理前", "处理后"), rows))
                .json(Map.of("simulateInline", inline, "stored", got))
                .notes(
                        "_simulate 是 pipeline 的单元测试：看得到每个字段处理后的值，且不产生任何写入",
                        "script 处理器能改已有字段、也能新增字段，但它是每次写入都要跑的代码 —— 别在里面做重活",
                        "pipeline 只能做「单条文档内」的加工；跨文档的关联（比如补齐品牌字典）要在写入前完成")
                .build();
    }

    private Map<String, Object> source() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("old_sku", "SKU-INGEST-1");
        d.put("title", "  蓝牙   耳机  主动降噪 ");
        d.put("price", 899);
        d.put("status", 1);
        d.put("internal_note", "内部备注：不要外发");
        return d;
    }
}
