package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
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
 * 五个处理器按顺序跑：补时间戳 → 改名 → 清洗空白 → 算含税价 → 删内部字段。
 * 本关不止「跑通」：还把每一步的中间态打出来（前缀 pipeline），
 * 并覆盖三条失败路径 —— 缺字段（默认 no-op / 严格模式报错）、类型错误（脚本炸）、
 * 以及给脚本挂 on_failure 后的降级结果。
 */
@Component
public class M113IngestPipeline implements LevelModule {

    private static final String PIPELINE = "eslab-goods-ingest";
    private static final String SAFE_PIPELINE = "eslab-goods-ingest-safe";
    private static final String INDEX = LabIndexes.INGEST;
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
        // @focus-start 五个处理器，拆成独立变量：后面要做「前缀 pipeline」和变体
        Map<String, Object> pSet = Json.map("set", Json.map(
                "field", "ingested_at", "value", "{{{_ingest.timestamp}}}"));
        Map<String, Object> pRename = Json.map("rename", Json.map("field", "old_sku", "target_field", "sku"));
        Map<String, Object> pGsub = Json.map("gsub", Json.map(
                "field", "title", "pattern", "\\s+", "replacement", " "));
        Map<String, Object> pTax = Json.map("script", Json.map(
                "source", "if (ctx.price != null) {"
                        + " ctx.price_with_tax = Math.round(ctx.price * 1.13 * 100) / 100.0; }"));
        Map<String, Object> pRemove = Json.map("remove", Json.map("field", "internal_note"));
        // @focus-end

        Map<String, Object> pipeline = Json.map(
                "description", "商品入库前的清洗与补算",
                "processors", List.of(pSet, pRename, pGsub, pTax, pRemove));
        es.put("/_ingest/pipeline/" + PIPELINE, pipeline);

        // @focus-start 先模拟：不改任何文档，直接看处理后的 _source
        JsonNode inline = simulate(pipeline, source());
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
        JsonNode simulated = inline.path("docs").path(0).path("doc").path("_source");

        Object tax = after.has("price_with_tax") ? after.get("price_with_tax").asDouble() : null;
        boolean hasOldSku = after.has("old_sku");
        boolean hasNote = after.has("internal_note");
        String title = after.path("title").asText("");
        String ingestedAt = after.path("ingested_at").asText("");

        // @focus-start 逐 processor 的中间态：前缀 pipeline 各 simulate 一次
        List<String> steps = new ArrayList<>();
        List<Map<String, Object>> stageProcessors = List.of(pSet, pRename, pGsub, pTax, pRemove);
        List<String> stageNames = List.of("set", "rename", "gsub", "script", "remove");
        for (int i = 1; i <= stageProcessors.size(); i++) {
            Map<String, Object> prefix = Json.map(
                    "processors", stageProcessors.subList(0, i));
            JsonNode r = simulate(prefix, source()).path("docs").path(0).path("doc").path("_source");
            steps.add(stageNames.get(i - 1) + " → sku=" + r.path("sku").asText("-")
                    + " · price_with_tax=" + r.path("price_with_tax").asText("-")
                    + " · note=" + (r.has("internal_note") ? "在" : "已删")
                    + " · ingested_at=" + (r.has("ingested_at") ? "有" : "无"));
        }
        // @focus-end

        // @focus-start 三条失败路径
        Map<String, Object> noSku = source();
        noSku.remove("old_sku");
        JsonNode simNoSku = simulate(pipeline, noSku);
        boolean noSkuOk = simNoSku.path("docs").path(0).has("error");

        Map<String, Object> lenientRename = Json.map("rename", Json.map(
                "field", "old_sku", "target_field", "sku", "ignore_missing", true));
        Map<String, Object> lenientPipeline = Json.map(
                "processors", List.of(pSet, lenientRename, pGsub, pTax, pRemove));
        JsonNode simLenient = simulate(lenientPipeline, noSku);
        boolean lenientOk = !simLenient.path("docs").path(0).has("error");
        String lenientSku = simLenient.path("docs").path(0).path("doc").path("_source").path("sku").asText("（无）");

        Map<String, Object> badPrice = source();
        badPrice.put("price", "899");
        JsonNode simBad = simulate(pipeline, badPrice);
        String badError = simBad.path("docs").path(0).path("error").path("type").asText("");

        Map<String, Object> safeScript = Json.map("script", Json.map(
                "source", "if (ctx.price != null) {"
                        + " ctx.price_with_tax = Math.round(ctx.price * 1.13 * 100) / 100.0; }",
                "on_failure", List.of(Json.map("set", Json.map(
                        "field", "ingest_failed", "value", "tax_calc")))));
        Map<String, Object> safePipeline = Json.map(
                "description", "脚本失败时降级继续",
                "processors", List.of(pSet, pRename, pGsub, safeScript, pRemove));
        es.put("/_ingest/pipeline/" + SAFE_PIPELINE, safePipeline);
        JsonNode simSafe = simulate(safePipeline, badPrice);
        boolean safeHasFlag = simSafe.path("docs").path(0).path("doc").path("_source")
                .path("ingest_failed").asText("").equals("tax_calc");
        boolean safeHasTax = simSafe.path("docs").path(0).path("doc").path("_source").has("price_with_tax");

        Map<String, Object> zero = source();
        zero.put("price", 0);
        String zeroTax = simulate(pipeline, zero).path("docs").path(0).path("doc").path("_source")
                .path("price_with_tax").asText("（未生成）");
        Map<String, Object> nullPrice = source();
        nullPrice.put("price", null);
        boolean nullHasTax = simulate(pipeline, nullPrice).path("docs").path(0).path("doc")
                .path("_source").has("price_with_tax");
        // @focus-end

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
        rows.add(TableData.row("缺 old_sku（默认）", "没有该字段",
                noSkuOk ? "报错：" + simNoSku.path("docs").path(0).path("error").path("type").asText()
                        : "不报错（异常）", "rename 默认就把缺字段当失败"));
        rows.add(TableData.row("缺 old_sku + ignore_missing:true", "没有该字段",
                lenientOk ? "不报错，sku=" + lenientSku : "仍然报错（异常）", "显式声明才变成 no-op"));
        rows.add(TableData.row("price 是字符串（无 on_failure）", "\"899\"",
                badError.isEmpty() ? "未报错（异常）" : badError, "脚本里做乘法，字符串直接炸"));
        rows.add(TableData.row("price 是字符串（带 on_failure）", "\"899\"",
                (safeHasFlag ? "降级继续，写入 ingest_failed=tax_calc" : "未降级")
                        + "；price_with_tax " + (safeHasTax ? "存在（异常）" : "缺失"),
                "on_failure 让这条文档仍能入库"));
        rows.add(TableData.row("price=0", "0", zeroTax, "边界：0 也算有值，会算出 0.0"));
        rows.add(TableData.row("price=null", "null",
                nullHasTax ? "生成了（异常）" : "不生成", "脚本里的 null 判断挡住"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("simulateInline", inline);
        json.put("stored", got);
        json.put("steps", steps);

        return LevelResult.builder("一条「脏」文档经过 5 个处理器后入库：改名、清空白、补含税价、删内部字段，"
                        + "含税价 " + (tax == null ? "未生成" : tax) + " 来自 price×1.13；"
                        + "缺字段默认就报错（要 ignore_missing:true 才跳过），类型错误会让整条文档失败，挂上 on_failure 才能降级继续")
                .metrics(
                        new Metric("处理器数", 5, "set / rename / gsub / script / remove"),
                        new Metric("price_with_tax", tax == null ? "—" : tax, "899 × 1.13"),
                        new Metric("缺字段", noSkuOk ? "默认报错" : "默认 no-op（异常）",
                                "ignore_missing:true 才跳过"),
                        new Metric("类型错误", badError.isEmpty() ? "未触发" : badError, "字符串 × 数字"),
                        new Metric("on_failure 降级", safeHasFlag ? "生效" : "未生效", "文档仍入库，标记 ingest_failed"),
                        new Metric("边界 price=0 / null", zeroTax + " / " + (nullHasTax ? "生成" : "不生成"),
                                "0 会算，null 跳过"))
                .table(TableData.of(List.of("字段 / 场景", "处理前", "处理后"), rows))
                .json(json)
                .notes(
                        "_simulate 是 pipeline 的单元测试：看得到每个字段处理后的值，且不产生任何写入",
                        "逐 processor 的中间态用「前缀 pipeline」拿：把前 N 个处理器拼成临时 pipeline 再 simulate，就能看到每一步的输入输出",
                        "rename 默认 ignore_missing=false：字段不存在时整条文档失败（本关实测 " + simNoSku.path("docs").path(0).path("error").path("type").asText("illegal_argument_exception") + "）；显式写 ignore_missing:true 才静默跳过 —— 两种行为都要知道",
                        "script 里对字段类型没有保护：price 若是字符串，乘法直接抛异常，整条文档被拒；要容错就得挂 on_failure 或自己判类型",
                        "on_failure 不是「忽略错误」：它让文档带着失败标记继续入库，事后可以按 ingest_failed 字段补算",
                        "pipeline 只能做「单条文档内」的加工；跨文档的关联（比如补齐品牌字典）要在写入前完成")
                .build();
    }

    /** 用内联 pipeline 模拟一条文档，返回完整响应（docs[0] 里可能是 doc，也可能是 error） */
    private JsonNode simulate(Map<String, Object> pipeline, Map<String, Object> source) {
        return es.post("/_ingest/pipeline/_simulate", Json.map(
                "pipeline", pipeline,
                "docs", List.of(Json.map("_source", source))));
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
