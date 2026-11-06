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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M1-1 动态映射会把你的数据「猜」成什么
 *
 * 这一关用最小样本复现真实线上事故：不写 mapping，直接灌数据，
 * 然后看 ES 猜出来的类型对不对、以及猜错之后查询为什么会静默返回 0 条。
 */
@Component
public class M11DynamicMapping implements LevelModule {

    private static final String INDEX = LabIndexes.DEMO_AUTO;

    private final EsClient es;
    private final LabIndexes indexes;

    public M11DynamicMapping(EsClient es, LabIndexes indexes) {
        this.es = es;
        this.indexes = indexes;
    }

    @Override
    public String id() {
        return "m1-1";
    }

    @Override
    public LevelResult run() throws Exception {
        deleteIndex();

        // @focus-start 建索引：只给分片数、不给 mappings —— 字段类型交给 ES 猜
        es.put("/" + INDEX, indexes.definitionOf(INDEX));
        // @focus-end

        // @focus-start 写入两条结构一致的商品：第 2 条的 price 传成了字符串，tags 传成了单值
        es.put("/" + INDEX + "/_doc/1", Json.map(
                "sku", "SKU-0001",
                "title", "小米 无线蓝牙耳机",
                "price", 299.0,
                "tags", Json.list("数码", "音频"),
                "status", 1,
                "created_at", "2026-10-01T10:00:00+08:00"));
        es.put("/" + INDEX + "/_doc/2", Json.map(
                "sku", "SKU-0002",
                "title", "机械键盘",
                "price", "599.00",
                "tags", "数码",
                "status", 3,
                "created_at", "2026-10-02"));
        // @focus-end

        JsonNode mapping = es.get("/" + INDEX + "/_mapping");
        JsonNode props = mapping.path(INDEX).path("mappings").path("properties");

        List<List<Object>> fieldRows = new ArrayList<>();
        props.fields().forEachRemaining(e -> {
            JsonNode def = e.getValue();
            String analyzer = def.path("analyzer").asText(
                    "text".equals(def.path("type").asText()) ? "standard(默认)" : "—");
            List<String> sub = new ArrayList<>();
            def.path("fields").fieldNames().forEachRemaining(sub::add);
            fieldRows.add(TableData.row(e.getKey(), def.path("type").asText("object"), analyzer,
                    sub.isEmpty() ? "无" : String.join(", ", sub)));
        });

        // @focus-start 三个探针：同样的数据，为什么 term 查不到？
        JsonNode probeTags = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "query", Json.map("term", Json.map("tags", "数码"))));
        JsonNode probeSku = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "query", Json.map("term", Json.map("sku", "SKU-0001"))));
        JsonNode probeMatch = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "query", Json.map("match", Json.map("title", "蓝牙耳机"))));
        // @focus-end

        String sortError = "";
        try {
            es.post("/" + INDEX + "/_search", Json.map("size", 1, "sort", Json.list(Json.map("title", "asc"))));
        } catch (EsException e) {
            sortError = e.getMessage();
        }

        long tagsHits = probeTags.path("hits").path("total").path("value").asLong();
        long skuHits = probeSku.path("hits").path("total").path("value").asLong();
        long matchHits = probeMatch.path("hits").path("total").path("value").asLong();

        deleteIndex();

        List<List<Object>> probeRows = new ArrayList<>();
        probeRows.add(TableData.row("term tags=\"数码\"", tagsHits,
                tagsHits == 0 ? "✗ text 字段被分词，词典里没有「数码」这个词项" : "✓"));
        probeRows.add(TableData.row("term sku=\"SKU-0001\"", skuHits,
                skuHits == 0 ? "✗ keyword 语义被做成了 text，大小写/分词全丢" : "✓"));
        probeRows.add(TableData.row("match title=\"蓝牙耳机\"", matchHits,
                "能查到，但靠的是 char 级分词碰运气"));
        probeRows.add(TableData.row("sort by title", sortError.isEmpty() ? "成功" : "400 报错",
                sortError.isEmpty() ? "" : "✗ text 字段没有 doc_values，不能直接排序"));

        return LevelResult.builder("不写 mapping 时，ES 会把 sku、tags 这类「看起来是字符串」的字段全部建成 text，"
                        + "于是精确匹配和排序全部失效")
                .metrics(
                        new Metric("字段数", props.size(), null),
                        new Metric("term 查询命中", tagsHits + " 条", "期望 2 条"),
                        new Metric("排序", sortError.isEmpty() ? "成功" : "被拒绝", "text 无 doc_values"))
                .table(new TableData(List.of("探针", "命中数 / 结果", "判定"), probeRows))
                .json(Map.of("mapping", props, "sortError", sortError))
                .notes(
                        "动态映射把 price 猜成 " + props.path("price").path("type").asText("?")
                                + "：第 2 条传字符串 \"599.00\" 之所以没报错，是 ES 的强制类型转换在兜底，不是设计正确。",
                        "tags 被猜成 " + props.path("tags").path("type").asText("?")
                                + "：数组被转成多值字段，单值「数码」也被当成一个词项，但经标准分词器处理后词项变成「数」「码」两个字，term 自然查不到。",
                        sortError.isEmpty() ? "排序未报错（与预期不符，请检查 mapping）" : "排序报错原文：" + sortError,
                        "这条索引在关卡结束后已被删除，避免污染实验环境。")
                .build();
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
