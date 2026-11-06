package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M1-4 字段爆炸：动态映射最贵的失败模式
 *
 * 真实事故形态：上游把一堆业务属性平铺成 attribute_1..attribute_N 写进来，
 * mapping 每来一个新 key 就长一个字段，最终撞上 index.mapping.total_fields.limit。
 */
@Component
public class M14FieldExplosion implements LevelModule {

    private static final String INDEX = "eslab-demo-explode";

    private final EsClient es;

    public M14FieldExplosion(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m1-4";
    }

    @Override
    public LevelResult run() throws Exception {
        deleteIndex();

        // @focus-start 反面教材：允许动态映射 + 把属性平铺成字段
        es.put("/" + INDEX, Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map("dynamic", true)));

        int written = 0;
        List<List<Object>> rows = new ArrayList<>();
        // 注意：动态映射下每个字符串字段会生成 text + keyword 两个字段，
        // 所以 450 个属性就已经消耗约 900 个字段，逼近默认上限 1000。
        for (int wave : new int[]{50, 150, 300, 450}) {
            StringBuilder ndjson = new StringBuilder();
            for (int i = written; i < wave * 2; i++) {
                ndjson.append("{\"index\":{\"_index\":\"").append(INDEX).append("\"}}\n");
                ndjson.append("{\"sku\":\"SKU-").append(i).append("\"");
                for (int f = 0; f < wave; f++) {
                    ndjson.append(",\"attribute_").append(f).append("\":\"v").append(f).append("\"");
                }
                ndjson.append("}\n");
            }
            JsonNode bulkRes = es.bulk(ndjson.toString(), 120_000);
            if (bulkRes.path("errors").asBoolean(false)) {
                throw new IllegalStateException("第 " + wave + " 波写入失败："
                        + firstBulkError(bulkRes));
            }
            written = wave * 2;
            es.post("/" + INDEX + "/_refresh", null);

            JsonNode m = es.get("/" + INDEX + "/_mapping");
            int count = countFields(m.path(INDEX).path("mappings").path("properties"));
            long docs = es.get("/" + INDEX + "/_count").path("count").asLong();
            rows.add(TableData.row(
                    "第 " + wave + " 个属性 × " + (wave * 2) + " 文档",
                    count,
                    docs,
                    count >= 800 ? "⚠ 逼近默认上限 1000" : ""));
        }
        // @focus-end

        // 再写 100 个从未出现过的新属性：这次会真的撞上 1000 字段上限
        String limitError = "";
        try {
            StringBuilder ndjson = new StringBuilder();
            ndjson.append("{\"index\":{\"_index\":\"").append(INDEX).append("\"}}\n");
            ndjson.append("{\"sku\":\"SKU-LIMIT\"");
            for (int f = 450; f < 550; f++) {
                ndjson.append(",\"attribute_").append(f).append("\":\"v").append(f).append("\"");
            }
            ndjson.append("}\n");
            JsonNode r = es.bulk(ndjson.toString(), 60_000);
            if (r.path("errors").asBoolean(false)) limitError = firstBulkError(r);
        } catch (EsException e) {
            limitError = e.getMessage();
        }

        // 反例：v2 是 dynamic:strict，写未知字段会直接报错
        String strictError = "";
        try {
            es.put("/eslab-products-v2/_doc/__probe__", Json.map(
                    "sku", "SKU-PROBE",
                    "title", "探测文档",
                    "unknown_field_xyz", 1,
                    "price", 1,
                    "category", "数码影音"));
        } catch (EsException e) {
            strictError = e.getMessage();
        }

        JsonNode m = es.get("/" + INDEX + "/_mapping");
        int fieldCount = countFields(m.path(INDEX).path("mappings").path("properties"));
        deleteIndex();

        return LevelResult.builder("字段爆炸的代价不是「字段多」，而是每个字段都要建索引结构："
                        + "cluster state 膨胀、每次 mapping 更新全集群广播、写入变慢，最后撞 1000 字段上限")
                .metrics(
                        new Metric("最终字段数", fieldCount, "含 keyword 子字段；默认上限 1000"),
                        new Metric("撞上限", limitError.isEmpty() ? "未触发" : "已拒绝", "再写 100 个新属性"),
                        new Metric("strict 拒写", strictError.isEmpty() ? "未拒绝" : "已拒绝", null))
                .table(new TableData(List.of("写入规模", "字段数（含子字段）", "累计文档", "状态"), rows))
                .json(Map.of("strictError", strictError, "limitError", limitError))
                .notes(
                        limitError.isEmpty()
                                ? "未触发字段上限（异常，请检查 ES 的 total_fields.limit）"
                                : "撞上限报错原文：" + limitError,
                        "注意：动态映射下每个字符串字段会生成 text + keyword 两个字段，所以 450 个属性就吃掉约 900 个名额 —— 上限算的是「字段数」，不是「属性数」。",
                        strictError.isEmpty()
                                ? "未触发 strict 报错（异常）"
                                : "v2 的 strict 报错原文：" + strictError,
                        "三种 dynamic 策略：true（默认，猜并建字段）、false（存 _source 但不建索引，查不到）、strict（拒写）。生产建议显式 mapping + strict。",
                        "如果业务确实需要任意属性（如商品自定义参数），正确做法是 nested/object 数组 + 固定字段，或扁平化成「属性名:值」的多值 keyword 字段。",
                        "本关索引已删除；v2 探测写入因 strict 被拒，不会留下脏数据。")
                .build();
    }

    /** 统计真实字段数：text 的 keyword 子字段也占一个名额，这正是「450 个属性吃掉 900 个字段」的原因 */
    private static int countFields(JsonNode props) {
        int n = 0;
        java.util.Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            JsonNode def = it.next().getValue();
            n++;
            if (def.has("properties")) n += countFields(def.get("properties"));
            if (def.has("fields")) n += def.get("fields").size();
        }
        return n;
    }

    /** 从 _bulk 响应里挑出第一条错误，展示真实的 status / type / reason */
    private static String firstBulkError(JsonNode res) {
        for (JsonNode item : res.path("items")) {
            java.util.Iterator<JsonNode> it = item.elements();
            if (!it.hasNext()) continue;
            JsonNode first = it.next();
            if (first.has("error")) {
                return first.path("status").asInt() + " "
                        + first.path("error").path("type").asText() + ": "
                        + first.path("error").path("reason").asText();
            }
        }
        return "批量里有失败条目，但未定位到具体原因";
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
