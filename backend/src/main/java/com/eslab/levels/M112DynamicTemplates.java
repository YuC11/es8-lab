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
 * m11-2：dynamic_templates 与 flattened。
 *
 * 商品属性天生是「半结构化」的：这周加个「降噪深度」，下周加个「充电协议」。
 * 三个选择都不完美：strict 直接拒收、裸 dynamic 字段爆炸、object 会跨元素误匹配。
 */
@Component
public class M112DynamicTemplates implements LevelModule {

    private static final String DYN = "eslab-goods-dyn";
    private static final String CTRL = "eslab-goods-dyn-ctrl";
    private static final int DOCS = 50;

    private static final String[] ATTR_KEYS = {
            "color", "material", "size", "weight", "origin", "warranty", "battery", "charging",
            "connectivity", "water_resistance", "noise_cancellation", "driver", "impedance",
            "frequency", "sensitivity", "cable_length", "plug_type", "mic", "control",
            "package", "certification", "origin_place", "shelf_life", "model"
    };
    private static final String[] COLORS = {"黑色", "白色", "蓝色", "红色", "银色"};

    private final EsClient es;
    private final LabData lab;

    public M112DynamicTemplates(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m11-2";
    }

    @Override
    public LevelResult run() throws Exception {
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < DOCS; i++) {
            ids.add("DYN-" + i);
            docs.add(doc(i));
        }
        // @focus-start 两个索引唯一的差别是：左边有 dynamic_templates，右边只有裸 dynamic
        // 定义都在 LabIndexes 里，这里只声明「要哪个索引」，重建 + 灌数据在一把锁内完成
        lab.rebuild(CTRL, idx -> lab.bulkWithIds(idx, ids, docs));
        lab.rebuild(DYN, idx -> lab.bulkWithIds(idx, ids, docs));
        // @focus-end

        JsonNode ctrlAttrs = mapping(CTRL).path("properties").path("attrs").path("properties");
        int ctrlSubFields = ctrlAttrs.size();
        int ctrlLeaves = 0;
        for (JsonNode child : ctrlAttrs) {
            ctrlLeaves += 1 + (child.path("fields").has("keyword") ? 1 : 0);
        }
        JsonNode dynAttrs = mapping(DYN).path("properties").path("attrs");
        String dynType = dynAttrs.path("type").asText("object");
        int dynSubFields = dynAttrs.path("properties").size();

        long qText = count(CTRL, Json.map("term", Json.map("attrs.color", "黑色")));
        long qKeyword = count(CTRL, Json.map("term", Json.map("attrs.color.keyword", "黑色")));
        long qFlat = count(DYN, Json.map("term", Json.map("attrs.color", "黑色")));

        // @focus-start 跨元素误命中：同一份数组对象，object 与 nested 各建一列
        long objCombo = 0;
        long nestedCombo = 0;
        long objColor = 0;
        long nestedColor = 0;
        try {
            lab.rebuild(LabIndexes.NESTED, idx -> lab.bulkWithIds(idx, NESTED_IDS, nestedDocs()));
            objCombo = count(LabIndexes.NESTED, Json.map("bool", Json.map("filter", List.of(
                    Json.map("term", Json.map("attrs_obj.color", "黑色")),
                    Json.map("term", Json.map("attrs_obj.material", "皮革"))))));
            nestedCombo = count(LabIndexes.NESTED, Json.map("nested", Json.map(
                    "path", "attrs_nested",
                    "query", Json.map("bool", Json.map("filter", List.of(
                            Json.map("term", Json.map("attrs_nested.color", "黑色")),
                            Json.map("term", Json.map("attrs_nested.material", "皮革"))))))));
            objColor = count(LabIndexes.NESTED, Json.map("term", Json.map("attrs_obj.color", "黑色")));
            nestedColor = count(LabIndexes.NESTED, Json.map("nested", Json.map(
                    "path", "attrs_nested",
                    "query", Json.map("term", Json.map("attrs_nested.color", "黑色")))));
        } finally {
            // 一次性实验索引：异常路径也要删
            lab.delete(LabIndexes.NESTED);
        }
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row(CTRL + " 的 attrs", ctrlSubFields + " 个子字段（叶子 " + ctrlLeaves + "）",
                "每个属性都被猜成 text + keyword"));
        rows.add(TableData.row(DYN + " 的 attrs", "1 个字段 · type=" + dynType,
                "整棵对象压成一个扁平字段"));
        rows.add(TableData.row("term attrs.color（对照）", fmt(qText) + " 条", "打 text 上：不分词，静默 0"));
        rows.add(TableData.row("term attrs.color.keyword（对照）", fmt(qKeyword) + " 条", "必须加 .keyword 才对"));
        rows.add(TableData.row("term attrs.color（flattened）", fmt(qFlat) + " 条", "字段名直接用，不用加子字段"));

        rows.add(TableData.row("object 组合条件（黑 + 皮革）", fmt(objCombo) + " 条",
                "跨元素误命中：两个条件落在不同数组元素上也算命中"));
        rows.add(TableData.row("nested 组合条件（黑 + 皮革）", fmt(nestedCombo) + " 条",
                "要求同一个元素同时满足 —— 这才是「同一条 sku 行」的语义"));
        rows.add(TableData.row("单条件对照（颜色=黑）", "object " + fmt(objColor) + " / nested " + fmt(nestedColor),
                "单条件时两者一致，差别只在组合条件上暴露"));

        return LevelResult.builder("同样的 24 个属性：裸 dynamic 长出 " + ctrlSubFields
                        + " 个子字段，flattened 只留 1 个；而查询写法反而更简单 —— 不用记 .keyword")
                .metrics(
                        new Metric("对照 attrs 子字段", ctrlSubFields, "叶子字段共 " + ctrlLeaves + " 个"),
                        new Metric("模板 attrs 类型", dynType, "整体当作一个字段"),
                        new Metric("term on text", fmt(qText) + " 条", "tokens 对不上，静默 0"),
                        new Metric("term on .keyword", fmt(qKeyword) + " 条", "整串相等"),
                        new Metric("term on flattened", fmt(qFlat) + " 条", "同样是 " + fmt(qFlat) + " 条，写法更短"))
                .table(TableData.of(List.of("检查项", "结果", "原因"), rows))
                .json(Json.map("controlSubFields", ctrlSubFields, "controlLeaves", ctrlLeaves,
                        "dynSubFields", dynSubFields, "dynType", dynType,
                        "nestedLab", Json.map("objCombo", objCombo, "nestedCombo", nestedCombo,
                                "objColor", objColor, "nestedColor", nestedColor)))
                .notes(
                        "dynamic_templates 是按顺序匹配的，第一个命中就停 —— flattened 规则必须放在 strings_as_keyword 前面",
                        "flattened 的代价：不能做范围查询、不能按子字段排序，只适合「查得到就行」的属性",
                        "如果属性之间要跨字段关联（比如「颜色是黑且材质是皮革」必须同一条 sku 行），那才需要 nested",
                        "本关用两条文档做了反例：数组里分别是 [{黑,皮革}] 与 [{黑,布},{白,皮革}]。object 上查「黑 + 皮革」命中 "
                                + objCombo + " 条（第二条是跨元素误命中），nested 上只有 " + nestedCombo
                                + " 条；而单条件「黑」两边都是 " + objColor + " 条 —— 差别只在组合条件上暴露")
                .build();
    }

    private static final List<String> NESTED_IDS = List.of("N-1", "N-2");

    /** 两条文档：一条同一元素同时满足「黑 + 皮革」，一条分属两个元素 */
    private static List<Map<String, Object>> nestedDocs() {
        return List.of(
                Json.map("sku", "N-1", "title", "同一元素满足",
                        "attrs_obj", List.of(Json.map("color", "黑色", "material", "皮革")),
                        "attrs_nested", List.of(Json.map("color", "黑色", "material", "皮革"))),
                Json.map("sku", "N-2", "title", "跨元素才满足",
                        "attrs_obj", List.of(Json.map("color", "黑色", "material", "布"),
                                Json.map("color", "白色", "material", "皮革")),
                        "attrs_nested", List.of(Json.map("color", "黑色", "material", "布"),
                                Json.map("color", "白色", "material", "皮革"))));
    }

    private Map<String, Object> doc(int i) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        for (int k = 0; k < ATTR_KEYS.length; k++) {
            attrs.put(ATTR_KEYS[k], valueOf(ATTR_KEYS[k], i, k));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sku", "DYN-" + i);
        out.put("title", "动态模板实验商品 " + i);
        out.put("attrs", attrs);
        return out;
    }

    private static String valueOf(String key, int i, int k) {
        if ("color".equals(key)) return COLORS[i % COLORS.length];
        return "v" + ((i + k) % 7);
    }

    private long count(String index, Object query) {
        return es.post("/" + index + "/_search",
                        Json.map("size", 0, "track_total_hits", true, "query", query))
                .path("hits").path("total").path("value").asLong(0);
    }

    private JsonNode mapping(String index) {
        return es.get("/" + index + "/_mapping").path(index).path("mappings");
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }
}
