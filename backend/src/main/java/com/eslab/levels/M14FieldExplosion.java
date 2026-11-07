package com.eslab.levels;

import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M1-4 字段爆炸：动态映射最贵的失败模式
 *
 * 真实事故形态：上游把一堆业务属性平铺成 attribute_1..attribute_N 写进来，
 * mapping 每来一个新 key 就长一个字段，最终撞上 index.mapping.total_fields.limit。
 *
 * 三种收法在同一关里对照（每种都真建索引、真写文档）：
 *   A 平铺：attribute_1..N 直接挂在根上 —— 字段数随属性数线性涨；
 *   B object 包一层：attrs.attribute_1..N —— 字段数一样涨，object 只是分组，不是压缩；
 *   C object + enabled:false —— 映射里只占 1 个字段，代价是这些属性彻底搜不到。
 * 另外记录「单文档字段数」与「索引字段数」两个数：撞上限的是后者，而前者决定了单条文档的写入成本。
 */
@Component
public class M14FieldExplosion implements LevelModule {

    private static final String INDEX = LabIndexes.DEMO_EXPLODE;
    private static final int[] WAVES = {50, 200, 400};
    private static final int OBJECT_ATTRS = 200;
    private static final int DARK_ATTRS = 400;

    private final EsClient es;
    private final LabIndexes indexes;
    private final ObjectMapper mapper;

    public M14FieldExplosion(EsClient es, LabIndexes indexes, ObjectMapper mapper) {
        this.es = es;
        this.indexes = indexes;
        this.mapper = mapper;
    }

    @Override
    public String id() {
        return "m1-4";
    }

    @Override
    public LevelResult run() throws Exception {
        List<List<Object>> rows = new ArrayList<>();
        int flatWaveFields = 0;
        int perDocFields = 0;
        int flatFieldCount = 0;
        int objectFields = 0;
        int darkFields = 0;
        long darkHits = 0;
        String limitError = "";
        String limitType = "";
        String limitReason = "";
        try {
            // ── A 平铺：属性名直接挂在根上 ──
            recreate(indexes.definitionOf(INDEX));
            for (int wave : WAVES) {
                int written = writeFlatDocs(wave);
                es.post("/" + INDEX + "/_refresh", null);
                flatFieldCount = countFields(mappingProps());
                perDocFields = fieldsOfFlatDoc(wave);
                rows.add(TableData.row("A 平铺", wave + " 个属性", perDocFields, flatFieldCount,
                        written + " 条", wave >= 400 ? "⚠ 逼近默认上限 1000" : ""));
                flatWaveFields = wave;
            }

            // 再写 150 个从没出现过的新属性：这次真的会撞上限
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("{\"index\":{\"_index\":\"").append(INDEX).append("\"}}\n");
                sb.append(flatDocJson("SKU-LIMIT", flatWaveFields, flatWaveFields + 150));
                sb.append('\n');
                JsonNode res = es.bulk(sb.toString(), 60_000);
                if (res.path("errors").asBoolean(false)) {
                    JsonNode first = firstError(res);
                    limitType = first.path("error").path("type").asText("");
                    limitReason = first.path("error").path("reason").asText("");
                    limitError = first.path("status").asInt(0) + " " + limitType;
                }
            } catch (EsException e) {
                limitError = e.status() + " " + errorType(e);
                limitReason = firstReason(e);
            }
            rows.add(TableData.row("A 平铺 + 150 新属性", (flatWaveFields + 150) + " 个属性", "—",
                    "未写入", "1 条", limitReason.isEmpty() ? "未触发上限" : clip(limitReason, 60)));

            // ── B object 包一层：属性全部塞进 attrs ──
            recreate(indexes.explodeObjectDefinition(true));
            writeObjectDocs(OBJECT_ATTRS);
            es.post("/" + INDEX + "/_refresh", null);
            objectFields = countFields(mappingProps());
            rows.add(TableData.row("B object 包一层", OBJECT_ATTRS + " 个属性", fieldsOfObjectDoc(OBJECT_ATTRS),
                    objectFields, "2 条", "字段数几乎不变 —— object 只是分组"));

            // ── C object + enabled:false：不建索引 ──
            recreate(indexes.explodeObjectDefinition(false));
            writeObjectDocs(DARK_ATTRS);
            es.post("/" + INDEX + "/_refresh", null);
            darkFields = countFields(mappingProps());
            darkHits = es.post("/" + INDEX + "/_search", Json.map("size", 0,
                    "query", Json.map("term", Json.map("attrs.attribute_1", "v1"))))
                    .path("hits").path("total").path("value").asLong();
            long darkDocs = es.get("/" + INDEX + "/_count").path("count").asLong();
            rows.add(TableData.row("C enabled:false", DARK_ATTRS + " 个属性", 1, darkFields,
                    darkDocs + " 条", "搜 attrs.attribute_1 命中 " + darkHits + " 条"));
        } finally {
            // 实验索引是一次性的：异常路径也必须删掉
            recreate(null);
        }

        String limit = extractLimit(limitReason);
        int limitNum = Integer.parseInt(limit);
        int perDocLimitRatio = limitNum > 0 ? Math.round((float) perDocFields / limitNum * 100) : 0;

        return LevelResult.builder("字段爆炸的代价不是「字段多」，而是每个字段都要建索引结构："
                        + "平铺 " + flatWaveFields + " 个属性就把索引字段数推到 " + flatFieldCount
                        + "（上限 " + limit + " 的 " + perDocLimitRatio + "%），再加 150 个新属性直接被拒绝；"
                        + "object 包一层照样涨，只有 enabled:false 能把字段数压回 " + darkFields
                        + " —— 代价是搜不到")
                .metrics(
                        new Metric("平铺 400 属性的索引字段数", flatFieldCount,
                                "上限 " + limit + "；" + perDocLimitRatio + "% 已用掉"),
                        new Metric("单文档字段数", perDocFields,
                                "400 个属性 × 2（text + keyword 子字段）"),
                        new Metric("撞上限", limitReason.isEmpty() ? "未触发" : "被拒绝",
                                limitReason.isEmpty() ? "" : clip(limitReason, 60)),
                        new Metric("object 包一层", objectFields + " 个字段",
                                "与平铺几乎相同：分组不等于压缩"),
                        new Metric("enabled:false", darkFields + " 个字段",
                                "属性全部进 _source，但一条也搜不到"))
                .table(new TableData(List.of("方案", "属性数", "单文档字段数", "索引字段数", "文档",
                        "结论 / 报错"), rows))
                .json(Json.map(
                        "limit", limit,
                        "limitError", Json.map("status", limitError, "type", limitType, "reason", limitReason),
                        "flatFields", flatFieldCount,
                        "objectFields", objectFields,
                        "darkFields", darkFields,
                        "darkHits", darkHits))
                .notes(
                        limitReason.isEmpty()
                                ? "本次没有触发字段上限（异常，请检查 index.mapping.total_fields.limit）"
                                : "撞上限的报错原文：" + clip(limitReason, 140)
                                + "。注意它说的是「total fields」，算的是整个索引的字段数，不是单条文档。",
                        "动态映射下每个字符串字段会生成 text + keyword 两个字段，所以 400 个属性就吃掉 800 个名额 —— "
                                + "本关单文档字段数 " + perDocFields + "，索引字段数 " + flatFieldCount
                                + "。这个 2 倍关系是「上限被提前用完」的直接原因。",
                        "object 包一层不解决问题：它只是把属性分组，子字段照样各自成字段（B 组 " + objectFields
                                + " 个字段）。真正的收法是 C 组的 enabled:false —— 映射里只占 1 个字段，"
                                + "但代价是这些属性连过滤都用不了，只能从 _source 里取。",
                        "生产上的第三条路是 flattened 类型或 dynamic_templates（m11-2 演示）："
                                + "把任意属性收进一个可搜索的扁平字段，兼顾数量与可用性。",
                        "结论边界：本关证明的是「字段数会撞上限」和「三种收法的字段数差异」，"
                                + "没有测量集群层面的影响（cluster state 大小、mapping 广播耗时）。"
                                + "说「字段多会拖垮集群」需要更大规模与专门的监控数据。",
                        "本关索引已删除；v2 未被写入。重复运行会重新建、重新删。")
                .build();
    }

    /** 建索引；传 null 表示只删除 */
    private void recreate(Map<String, Object> definition) {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
        if (definition != null) es.put("/" + INDEX, definition);
    }

    /** 写 2 条带 wave 个属性的文档，返回成功条数 */
    private int writeFlatDocs(int wave) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            sb.append("{\"index\":{\"_index\":\"").append(INDEX).append("\"}}\n");
            sb.append(flatDocJson("SKU-" + wave + "-" + i, 0, wave));
            sb.append('\n');
        }
        JsonNode res = es.bulk(sb.toString(), 120_000);
        int ok = 0;
        for (JsonNode item : res.path("items")) {
            Iterator<JsonNode> it = item.elements();
            if (it.hasNext() && it.next().path("status").asInt(500) < 300) ok++;
        }
        if (ok < 2) throw new IllegalStateException("写入失败：" + firstError(res));
        return ok;
    }

    private String flatDocJson(String sku, int from, int to) throws Exception {
        Map<String, Object> doc = Json.map("sku", sku);
        for (int f = from; f < to; f++) doc.put("attribute_" + f, "v" + f);
        return mapper.writeValueAsString(doc);
    }

    /** 同样数量，但属性塞进 attrs 对象里 */
    private void writeObjectDocs(int attrs) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> inner = Json.map();
            for (int f = 0; f < attrs; f++) inner.put("attribute_" + f, "v" + f);
            sb.append("{\"index\":{\"_index\":\"").append(INDEX).append("\"}}\n");
            sb.append(mapper.writeValueAsString(Json.map("attrs", inner)));
            sb.append('\n');
        }
        JsonNode res = es.bulk(sb.toString(), 120_000);
        if (res.path("errors").asBoolean(false)) {
            throw new IllegalStateException("B/C 组写入失败：" + firstError(res));
        }
    }

    private JsonNode mappingProps() {
        return es.get("/" + INDEX + "/_mapping").path(INDEX).path("mappings").path("properties");
    }

    /** 单文档字段数：1 个 sku + n 个属性，字符串各带一个 keyword 子字段 */
    private static int fieldsOfFlatDoc(int attrs) {
        return 2 + attrs * 2;
    }

    private static int fieldsOfObjectDoc(int attrs) {
        return 1 + attrs * 2;
    }

    /** 统计真实字段数：text 的 keyword 子字段也占一个名额 */
    private static int countFields(JsonNode props) {
        int n = 0;
        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            JsonNode def = it.next().getValue();
            n++;
            if (def.has("properties")) n += countFields(def.get("properties"));
            if (def.has("fields")) n += def.get("fields").size();
        }
        return n;
    }

    private static JsonNode firstError(JsonNode res) {
        for (JsonNode item : res.path("items")) {
            Iterator<JsonNode> it = item.elements();
            if (!it.hasNext()) continue;
            JsonNode first = it.next();
            if (first.has("error")) return first;
        }
        return res;
    }

    /** 从报错里取出上限数字（"Limit of total fields [1000] has been exceeded"） */
    private static String extractLimit(String reason) {
        Matcher m = Pattern.compile("\\[(\\d+)]").matcher(reason);
        return m.find() ? m.group(1) : "1000";
    }

    private static String errorType(EsException e) {
        JsonNode payload = e.payload();
        if (payload != null && payload.has("error")) {
            return payload.path("error").path("type").asText("error");
        }
        return "error";
    }

    private static String firstReason(EsException e) {
        JsonNode payload = e.payload();
        if (payload != null && payload.path("error").path("root_cause").isArray()
                && !payload.path("error").path("root_cause").isEmpty()) {
            return payload.path("error").path("root_cause").path(0).path("reason").asText("");
        }
        return e.getMessage() == null ? "" : e.getMessage();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
