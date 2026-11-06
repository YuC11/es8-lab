package com.eslab.levels;

import com.eslab.data.DatasetService;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M1-2 显式 Mapping：逐字段做决策，而不是让 ES 猜
 *
 * 这一关把 v1（动态）和 v2（显式）的字段类型摆在一起对照，
 * 并给出每一处类型选择的理由——类型不是「选个像的」，而是由访问方式决定的。
 */
@Component
public class M12ExplicitMapping implements LevelModule {

    private static final String V1 = "eslab-products-v1";
    private static final String V2 = "eslab-products-v2";

    private static final Map<String, String> REASON = Map.ofEntries(
            Map.entry("sku", "业务主键，只做精确匹配与去重 → keyword"),
            Map.entry("title", "要分词搜，也要按标题精确聚合 → text + keyword 子字段"),
            Map.entry("description", "只做全文检索，不需要精确值 → 纯 text"),
            Map.entry("category", "类目固定枚举，只做过滤与聚合 → keyword"),
            Map.entry("brand", "同上 → keyword"),
            Map.entry("price", "金额要范围过滤与求和，scaled_float 比 double 省空间且无精度噪声"),
            Map.entry("stock", "整数计数，范围过滤 → integer"),
            Map.entry("status", "五档枚举，需要过滤但基数极低 → integer（见 M7 的 BKD 讨论）"),
            Map.entry("tags", "多值标签，精确匹配与聚合 → keyword"),
            Map.entry("created_at", "时间范围过滤与按天聚合 → date"),
            Map.entry("rating", "评分 0–5，一位小数 → half_float"),
            Map.entry("sales", "销量整数 → integer"),
            Map.entry("seller_id", "商家 ID 精确匹配 → keyword"),
            Map.entry("specs", "固定结构的对象 → object（不要盲目上 nested）"));

    private final EsClient es;
    private final DatasetService dataset;

    public M12ExplicitMapping(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m1-2";
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(V1)) {
            throw new IllegalStateException("v1 还不存在：先完成第 0 章，把数据建出来");
        }

        // @focus-start 这一步真的重建 v2：显式 Mapping + 3 分片，然后把同一批数据再灌一遍
        boolean created = false;
        if (!dataset.exists(V2)) {
            dataset.create("v2");
            created = true;
        }
        long v1Count = dataset.count(V1);
        long v2Count = dataset.count(V2);
        int target = (int) Math.min(v1Count, DatasetService.DEFAULT_TARGET);
        int loaded = 0;
        if (v2Count < target) {
            loaded = dataset.bulkLoad(V2, (int) (target - v2Count), (int) v2Count, null).written();
        }
        // @focus-end

        JsonNode m1 = es.get("/" + V1 + "/_mapping");
        JsonNode m2 = es.get("/" + V2 + "/_mapping");
        JsonNode p1 = m1.path(V1).path("mappings").path("properties");
        JsonNode p2 = m2.path(V2).path("mappings").path("properties");

        Set<String> fields = new LinkedHashSet<>();
        p1.fieldNames().forEachRemaining(fields::add);
        p2.fieldNames().forEachRemaining(fields::add);

        List<List<Object>> rows = new ArrayList<>();
        for (String f : fields) {
            rows.add(TableData.row(f, describe(p1.get(f)), describe(p2.get(f)),
                    REASON.getOrDefault(f, "")));
        }

        JsonNode settings2 = es.get("/" + V2 + "/_settings");
        JsonNode indexSettings = settings2.path(V2).path("settings").path("index");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v2Mapping", p2);
        json.put("v2Settings", indexSettings);

        return LevelResult.builder("Mapping 的每个类型都能从「这个字段怎么被查」反推出来："
                        + "要分词→text，要精确/排序/聚合→keyword，要范围→数值或日期")
                .metrics(
                        new Metric("v1 字段数", p1.size(), null),
                        new Metric("v2 字段数", p2.size(), "顶层字段，specs 下还有子字段"),
                        new Metric("v2 分片数", indexSettings.path("number_of_shards").asText("?"), null),
                        new Metric("v2 文档数", dataset.count(V2), (created ? "本关新建，写入 " : "已有 ") + loaded + " 条"))
                .table(new TableData(List.of("字段", "v1 动态推测", "v2 显式声明", "为什么这样选"), rows))
                .json(json)
                .notes(
                        "v1 里 tags 是 text、sku 是 text，这两个错误会一路传导到查询、聚合、排序三个层面。",
                        "v2 里 title 同时有 text 和 keyword 两个视角：搜索走 text，排序/聚合走 title.keyword，这是最常用的双写模式。",
                        "注意 v2 的 dynamic: strict —— 写错字段名会直接 400，而不是悄悄多出一个字段。代价是新字段上线必须先改 mapping。",
                        created ? "本关真的执行了 PUT /" + V2 + " 并灌入 " + loaded + " 条，所以在数据工坊里能看到它。"
                                : "v2 已存在，本关没有重建，只是对比。想重来就在数据工坊删掉 v2。")
                .build();
    }

    private static String describe(JsonNode def) {
        if (def == null || def.isMissingNode() || def.isNull()) return "（不存在）";
        if (def.has("properties")) {
            return "object(" + def.path("properties").size() + " 子字段)";
        }
        StringBuilder sb = new StringBuilder(def.path("type").asText("object"));
        if (def.hasNonNull("analyzer")) sb.append("(").append(def.get("analyzer").asText()).append(")");
        if (def.has("fields")) {
            List<String> subs = new ArrayList<>();
            def.path("fields").fieldNames().forEachRemaining(subs::add);
            sb.append(" + ").append(String.join(",", subs));
        }
        return sb.toString();
    }
}
