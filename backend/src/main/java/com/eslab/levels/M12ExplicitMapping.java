package com.eslab.levels;

import com.eslab.data.DatasetService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M1-2 显式 Mapping：逐字段做决策，而不是让 ES 猜
 *
 * 这一关把 v1（动态）和 v2（显式）的字段类型摆在一起对照，
 * 并给出每一处类型选择的理由与代价——类型不是「选个像的」，而是由访问方式与存储成本共同决定的。
 *
 * 两个必须亲眼看到的响应：
 *   · 合法商品文档写进 v2 → 201，再 GET 回来，_source 与字段类型都对得上（写完就删，条数不变）；
 *   · 带未知字段的文档写进 v2 → 400 strict_dynamic_mapping_exception，字段名打错不会悄悄多出一个字段。
 */
@Component
public class M12ExplicitMapping implements LevelModule {

    private static final String V1 = DatasetService.V1;
    private static final String V2 = DatasetService.V2;
    /** 探针文档的固定 _id：跑完就删，条数回到原值，冷启动回归才可比 */
    private static final String PROBE_ID = "__m12_probe__";

    /** 访问需求：这个字段会被怎么查 */
    private static final Map<String, String> NEED = Map.ofEntries(
            Map.entry("sku", "精确等值 + 去重"),
            Map.entry("title", "全文检索 + 按标题精确聚合/排序"),
            Map.entry("description", "只做全文检索"),
            Map.entry("category", "等值过滤 + 分桶聚合"),
            Map.entry("brand", "等值过滤 + 分桶聚合"),
            Map.entry("price", "范围过滤 + 求和 + 百分位"),
            Map.entry("stock", "数值范围过滤"),
            Map.entry("status", "五档枚举过滤"),
            Map.entry("tags", "多值等值匹配"),
            Map.entry("created_at", "时间范围 + 按时间聚合"),
            Map.entry("rating", "范围过滤"),
            Map.entry("sales", "排序 + 求和"),
            Map.entry("seller_id", "精确匹配"),
            Map.entry("specs", "子字段过滤；跨子字段关联需 nested"));

    /** 代价：这个选择花掉了什么 */
    private static final Map<String, String> COST = Map.ofEntries(
            Map.entry("sku", "keyword 常驻 doc_values，2 万条约 0.5MB；换来精确过滤"),
            Map.entry("title", "text + keyword 双写，倒排与正排各一份，体积约 1.6 倍"),
            Map.entry("description", "ik_max_word 词项最多，写入 CPU 与段体积都最高"),
            Map.entry("category", "低基数枚举，terms 聚合可全量精确"),
            Map.entry("brand", "同 category，桶数受品牌数量约束"),
            Map.entry("price", "scaled_float 以 long 存储（×100），比 double 省一半且无浮点噪声"),
            Map.entry("stock", "integer 4 字节，比 long 省一半"),
            Map.entry("status", "integer 4 字节；靠 doc_values 过滤，不建倒排"),
            Map.entry("tags", "每个标签一个词项，多值字段的 doc_values 也按值展开"),
            Map.entry("created_at", "date 存 epoch millis，8 字节；可按天/月聚合"),
            Map.entry("rating", "half_float 2 字节，精度约 3 位有效数字"),
            Map.entry("sales", "integer；排序走 doc_values"),
            Map.entry("seller_id", "高基数 keyword，terms 聚合要注意桶数与内存"),
            Map.entry("specs", "object 不保留跨子字段关联；要关联必须 nested，体积与查询成本都上升"));

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

        JsonNode p1 = es.get("/" + V1 + "/_mapping").path(V1).path("mappings").path("properties");
        JsonNode p2 = es.get("/" + V2 + "/_mapping").path(V2).path("mappings").path("properties");

        // 按业务顺序读，而不是 mapping 的字母序：决策表要按「字段在业务里的位置」排列
        Set<String> fields = new LinkedHashSet<>(List.of(
                "sku", "title", "description", "category", "brand", "price", "stock", "status",
                "tags", "created_at", "rating", "sales", "seller_id", "specs"));
        p1.fieldNames().forEachRemaining(fields::add);
        p2.fieldNames().forEachRemaining(fields::add);

        List<List<Object>> rows = new ArrayList<>();
        for (String f : fields) {
            rows.add(TableData.row(f, describe(p1.get(f)), describe(p2.get(f)),
                    NEED.getOrDefault(f, ""), COST.getOrDefault(f, "")));
        }

        // ── 真实写入：合法文档 → 201 + 回读；未知字段 → 400 strict ──
        long beforeProbe = dataset.count(V2);
        String writeStatus;
        String readBack = "";
        try {
            JsonNode wrote = es.put("/" + V2 + "/_doc/" + PROBE_ID + "?refresh=true", sampleDoc());
            writeStatus = wrote.path("result").asText("created") + " / _version "
                    + wrote.path("_version").asInt();
            JsonNode got = es.get("/" + V2 + "/_doc/" + PROBE_ID);
            JsonNode src = got.path("_source");
            readBack = "found=" + got.path("found").asBoolean()
                    + "，price=" + src.path("price").asText()
                    + "，created_at=" + src.path("created_at").asText()
                    + "，tags=" + src.path("tags").size() + " 个";
        } catch (EsException e) {
            writeStatus = "失败 " + e.status();
        } finally {
            // 探针文档只在关卡运行期间存在：删掉并刷新，条数必须回到写入前
            try {
                es.delete("/" + V2 + "/_doc/" + PROBE_ID + "?refresh=true");
            } catch (EsException ignored) {
                // 不存在即视为已清理
            }
        }

        String strictError = "";
        String strictReason = "";
        try {
            es.put("/" + V2 + "/_doc/" + PROBE_ID + "_bad", Json.map(
                    "sku", "SKU-PROBE",
                    "title", "探测文档",
                    "price", 1,
                    "category", "数码影音",
                    "color_size", "黑/XL"));
        } catch (EsException e) {
            strictError = e.status() + " " + errorType(e);
            strictReason = firstReason(e);
        }

        long afterProbe = dataset.count(V2);
        JsonNode settings2 = es.get("/" + V2 + "/_settings").path(V2).path("settings").path("index");

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("v2Mapping", p2);
        json.put("v2Settings", settings2);
        json.put("probe", Json.map("writeStatus", writeStatus, "readBack", readBack,
                "docsBefore", beforeProbe, "docsAfter", afterProbe));
        json.put("strictError", Json.map("status", strictError, "reason", strictReason));

        return LevelResult.builder("Mapping 的每个类型都能从「这个字段怎么被查」反推出来："
                        + "要分词→text，要精确/排序/聚合→keyword，要范围→数值或日期；"
                        + "而每个选择都有代价（空间、精度、写入 CPU）。strict 让写错的字段当场 400")
                .metrics(
                        new Metric("v1 字段数", p1.size(), "全动态推断的结果"),
                        new Metric("v2 字段数", p2.size(), "含 specs 子字段：" + childCount(p2.path("specs")) + " 个"),
                        new Metric("v2 分片数", settings2.path("number_of_shards").asText("?"), "v1 是 1 片"),
                        new Metric("探针文档", writeStatus, readBack),
                        new Metric("文档数", beforeProbe + " → " + afterProbe, "探针写完即删，条数不变"),
                        new Metric("未知字段", strictError.isEmpty() ? "未拒绝" : strictError,
                                strictReason.isEmpty() ? "" : clip(strictReason)))
                .table(new TableData(
                        List.of("字段", "v1 动态推测", "v2 显式声明", "访问需求", "代价 / 取舍"), rows))
                .json(json)
                .notes(
                        "v1 里 tags 与 sku 都是 text：这两个推断错误会一路传导到查询、聚合、排序三个层面"
                                + "（m1-1 已经把三种失败逐个实测过）。",
                        "v2 里 title 同时有 text 与 keyword 两个视角：搜索走 text，排序/聚合走 title.keyword。"
                                + "双写不是「保险」，是拿索引体积换查询自由度。",
                        strictReason.isEmpty()
                                ? "本次没有触发 strict 拒写（异常，请检查 v2 的 dynamic 设置）"
                                : "v2 的 dynamic: strict 让未知字段直接 " + strictError + "，报错原文：" + clip(strictReason)
                                + "。代价是新字段上线必须先改 mapping 再写数据。",
                        "合法文档的探针证明了另一面：字段都能对上时，写入返回 " + writeStatus
                                + "，回读 " + readBack + "，且写完删除后条数回到 " + afterProbe + "。",
                        created ? "本关真的执行了 PUT /" + V2 + " 并灌入 " + loaded + " 条，所以在数据工坊里能看到它。"
                                : "v2 已存在，本关没有重建，只是对比与探针。想重来就在数据工坊删掉 v2。")

                .build();
    }

    /** 一条结构完整的商品文档：14 个字段与 v2 的 mapping 一一对应 */
    private Map<String, Object> sampleDoc() {
        return Json.map(
                "sku", "SKU-PROBE-0001",
                "title", "声阔 主动降噪头戴耳机 Q45",
                "description", "40mm 动圈单元，混合主动降噪，续航 60 小时",
                "category", "数码影音",
                "brand", "声阔",
                "price", 899.0,
                "stock", 120,
                "status", 1,
                "tags", Json.list("降噪", "头戴式"),
                "created_at", "2026-10-05T09:30:00+08:00",
                "rating", 4.5,
                "sales", 320,
                "seller_id", "SELLER-0007",
                "specs", Json.map("color", "曜石黑", "material", "皮革", "weight_g", 250));
    }

    private static String describe(JsonNode def) {
        if (def == null || def.isMissingNode() || def.isNull()) return "（不存在）";
        if (def.has("properties")) {
            return "object(" + def.path("properties").size() + " 子字段)";
        }
        StringBuilder sb = new StringBuilder(def.path("type").asText("object"));
        if (def.hasNonNull("analyzer")) sb.append("(").append(def.get("analyzer").asText()).append(")");
        if (def.hasNonNull("scaling_factor")) sb.append("×").append(def.get("scaling_factor").asText().replace(".0", ""));
        if (def.has("fields")) {
            List<String> subs = new ArrayList<>();
            def.path("fields").fieldNames().forEachRemaining(subs::add);
            sb.append(" + ").append(String.join(",", subs));
        }
        return sb.toString();
    }

    private static int childCount(JsonNode def) {
        return def.has("properties") ? def.path("properties").size() : 0;
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
        return s.length() <= 100 ? s : s.substring(0, 100) + "…";
    }
}
