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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M1-1 动态映射会把你的数据「猜」成什么
 *
 * 这一关用最小样本复现真实线上事故：不写 mapping，直接灌数据，
 * 然后看 ES 猜出来的类型对不对、以及猜错之后查询为什么会静默返回 0 条。
 *
 * 两个层次要分清，否则会把「推断错」和「用法错」混成一件事：
 *   · 类型推断：字符串一律给 text + keyword 子字段，带小数的数字给 float（不是 double），
 *     整数给 long（不是 integer），「2026-10-02」这种串会被认成 date；
 *   · 查询语义：同一个 term 查询，打在 text 上查的是分词后的词项，打在 .keyword 上查的是原始串。
 * 所以表格按字段成行，每行末尾直接给「这个字段上的探针跑出什么」，一个字段一行证据。
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

        // 两条结构基本一致的商品：第 2 条把数字写成字符串、数组写成单值，还多出一个新字段
        es.put("/" + INDEX + "/_doc/1", Json.map(
                "sku", "SKU-0001",
                "title", "小米 无线蓝牙耳机",
                "price", 299.0,
                "stock", 120,
                "status", 1,
                "tags", Json.list("数码", "音频"),
                "created_at", "2026-10-01T10:00:00+08:00",
                "specs", Json.map("color", "曜石黑", "weight_g", 250)));
        es.put("/" + INDEX + "/_doc/2", Json.map(
                "sku", "SKU-0002",
                "title", "机械键盘 87 键",
                "price", "599.00",
                "stock", "88",
                "status", 3,
                "tags", "数码",
                "created_at", "2026-10-02",
                "promo", true));
        // @focus-end

        // 不 refresh 就查，等于在测「1 秒可见性」而不是映射 —— 这一关的探针必须先落段
        es.post("/" + INDEX + "/_refresh", null);

        JsonNode props = es.get("/" + INDEX + "/_mapping")
                .path(INDEX).path("mappings").path("properties");

        // @focus-start 探针：同一份数据上，text 侧与 keyword 侧、文本与数值各查一次
        long termSku = count(Json.map("term", Json.map("sku", "SKU-0001")));
        long termSkuKw = count(Json.map("term", Json.map("sku.keyword", "SKU-0001")));
        long termTags = count(Json.map("term", Json.map("tags", "数码")));
        long termTagsKw = count(Json.map("term", Json.map("tags.keyword", "数码")));
        long matchTitle = count(Json.map("match", Json.map("title", "蓝牙耳机")));
        long rangePrice = count(Json.map("range", Json.map("price", Json.map("gte", 500))));
        long rangeStock = count(Json.map("range", Json.map("stock", Json.map("gte", 100))));
        long termStatus = count(Json.map("term", Json.map("status", 3)));
        long rangeDate = count(Json.map("range", Json.map("created_at", Json.map("gte", "2026-10-02"))));
        long termPromo = count(Json.map("term", Json.map("promo", true)));
        long termColor = count(Json.map("term", Json.map("specs.color", "曜石黑")));
        // @focus-end

        String sortTextError = "";
        String sortTextReason = "";
        try {
            es.post("/" + INDEX + "/_search", Json.map("size", 1, "sort", Json.list(Json.map("sku", "asc"))));
        } catch (EsException e) {
            sortTextError = e.status() + " " + errorType(e);
            sortTextReason = firstReason(e);
        }
        long sortKeywordHits = 0;
        String sortKeywordError = "";
        try {
            sortKeywordHits = es.post("/" + INDEX + "/_search",
                    Json.map("size", 1, "sort", Json.list(Json.map("sku.keyword", "asc"))))
                    .path("hits").path("hits").size();
        } catch (EsException e) {
            sortKeywordError = e.status() + " " + errorType(e);
        }

        // 类型一旦定型就改不回来：status 已被推断成 long，再写非数字直接 400
        String coerceError = "";
        String coerceReason = "";
        try {
            es.put("/" + INDEX + "/_doc/3", Json.map("sku", "SKU-0003", "status", "未知"));
        } catch (EsException e) {
            coerceError = e.status() + " " + errorType(e);
            coerceReason = firstReason(e);
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(fieldRow(props, "sku", "SKU-0001（字符串）",
                "term=" + termSku + " / term.keyword=" + termSkuKw
                        + " / sort=" + (sortTextError.isEmpty() ? "成功" : "拒绝")
                        + "（.keyword 排序 " + (sortKeywordError.isEmpty() ? "成功" : "拒绝") + "）",
                "业务主键被当全文：精确匹配、排序都得绕到 .keyword"));
        rows.add(fieldRow(props, "title", "小米 无线蓝牙耳机（字符串）",
                "match=" + matchTitle + " / sort=" + (sortTextError.isEmpty() ? "成功" : "拒绝"),
                "搜索可用，但排序/聚合必须走 .keyword"));
        rows.add(fieldRow(props, "price", "299.0 数字 / \"599.00\" 字符串",
                "range ≥500 = " + rangePrice, "float 只有 24 位有效位，金额精度由 ES 决定"));
        rows.add(fieldRow(props, "stock", "120 数字 / \"88\" 字符串",
                "range ≥100 = " + rangeStock, "long，比 integer 多一倍空间"));
        rows.add(fieldRow(props, "status", "1 / 3（整数）",
                "term=3 → " + termStatus, "long，枚举值不需要这么大"));
        rows.add(fieldRow(props, "tags", "[\"数码\",\"音频\"] 数组 / \"数码\" 单值",
                "term=" + termTags + " / term.keyword=" + termTagsKw, "数组拍平成多值，text 侧查不到整词"));
        rows.add(fieldRow(props, "created_at", "带时区时间戳 / 只到天",
                "range ≥2026-10-02 = " + rangeDate, "date，两种写法都被识别"));
        rows.add(fieldRow(props, "promo", "第 2 条才出现的新字段",
                "term=true → " + termPromo, "新键当场建字段，是字段爆炸的入口"));
        rows.add(fieldRow(props, "specs.color", "嵌套对象里的字符串",
                "term=曜石黑 → " + termColor, "子字段也被建成 text：对象不改变推断规则"));

        deleteIndex();

        int textFields = textCount(props);
        return LevelResult.builder("不写 mapping 时，" + textFields + " 个字符串字段全被建成 text，"
                        + "精确匹配、排序、聚合三种用法同时失效；只有 .keyword 子字段还能用——"
                        + "而数字与日期被猜成 long / float / date，看起来对，但精度与范围已由 ES 决定")
                .metrics(
                        new Metric("顶层字段数", props.size(), "第 2 条多出的 promo 被当场加进来"),
                        new Metric("被猜成 text", textFields + " 个", "都带 keyword 子字段"),
                        new Metric("term 查 sku", termSku + " 条", "改查 sku.keyword：" + termSkuKw + " 条"),
                        new Metric("text 排序", sortTextError.isEmpty() ? "成功" : "被拒绝", "fielddata 默认关闭"),
                        new Metric("doc 2 的 price", "\"599.00\" 字符串", "仍按 float 比较，range 命中 "
                                + rangePrice + " 条"),
                        new Metric("类型冲突", coerceError.isEmpty() ? "未触发" : coerceError,
                                "status 已被定型为 long"))
                .table(new TableData(
                        List.of("字段", "写入的值", "推断类型", "子字段", "探针（同一份数据）", "后果"), rows))
                .json(json(props, sortTextError, sortTextReason, coerceError, coerceReason))
                .notes(
                        "推断规则只有四条要看：字符串 → text + keyword 子字段；带小数点的数字 → float（不是 double）；"
                                + "整数 → long（不是 integer）；能被识别的日期串 → date。"
                                + "本关实际结果是 price=" + type(props, "price") + "、stock=" + type(props, "stock")
                                + "、status=" + type(props, "status") + "、created_at=" + type(props, "created_at") + "。",
                        "「推断对了」和「查询对了」是两件事：price 被推断成 float，range 按数值比较没问题（"
                                + rangePrice + " 条）；sku 被推断成 text + keyword，term 查 text 是 " + termSku
                                + " 条、查 keyword 是 " + termSkuKw + " 条 —— 同一条数据，两种写法两种结果。",
                        coerceReason.isEmpty()
                                ? "本次没有触发类型冲突（异常，请检查 status 字段的类型）"
                                : "类型定型后不能反悔：status 先被推断成 long，再写「未知」直接 " + coerceError
                                + "，报错原文：" + clip(coerceReason),
                        "第 2 条的 price 传成字符串 \"599.00\" 却没报错，是 ES 的强制类型转换在兜底；"
                                + "这不是「设计正确」，而是把错误推迟到查询阶段才暴露。",
                        "specs.color 的 term 查询命中 " + termColor + " 条：object 只做分组，子字段照样按字符串规则被建成 text。"
                                + "要精确过滤对象里的颜色，必须显式声明 keyword。",
                        "本关索引已删除（一次性演示索引），不会污染后续章节。")

                .build();
    }

    private Map<String, Object> json(JsonNode props, String sortTextError, String sortTextReason,
                                     String coerceError, String coerceReason) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mapping", props);
        out.put("sortTextError", sortTextError);
        out.put("sortTextReason", sortTextReason);
        out.put("coerceError", coerceError);
        out.put("coerceReason", coerceReason);
        return out;
    }

    /** 一个字段一行：类型、子字段、这个字段上的探针结果、后果 */
    private static List<Object> fieldRow(JsonNode props, String field, String written,
                                         String probe, String consequence) {
        JsonNode def;
        if (field.contains(".")) {
            String[] parts = field.split("\\.");
            def = props.path(parts[0]).path("properties").path(parts[1]);
        } else {
            def = props.path(field);
        }
        String type = def.path("type").asText(def.has("properties") ? "object" : "?");
        List<String> subs = new ArrayList<>();
        def.path("fields").fieldNames().forEachRemaining(subs::add);
        return TableData.row(field, written, type, subs.isEmpty() ? "无" : String.join(", ", subs),
                probe, consequence);
    }

    private long count(Object query) {
        JsonNode res = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true, "query", query));
        return res.path("hits").path("total").path("value").asLong();
    }

    private static int textCount(JsonNode props) {
        int n = 0;
        java.util.Iterator<JsonNode> it = props.elements();
        while (it.hasNext()) {
            if ("text".equals(it.next().path("type").asText())) n++;
        }
        return n;
    }

    private static String type(JsonNode props, String field) {
        return props.path(field).path("type").asText(props.path(field).has("properties") ? "object" : "?");
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

    private static String clip(String s) {
        return s.length() <= 90 ? s : s.substring(0, 90) + "…";
    }

    private void deleteIndex() {
        try {
            es.delete("/" + INDEX);
        } catch (EsException ignored) {
            // 不存在即视为已清理
        }
    }
}
