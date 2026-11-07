package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5-2 排序与聚合为什么都要 keyword：doc_values 是那道门槛
 *
 * 同一个字段，两种用法各试一次：
 *   排序（sort）   → 需要按值取顺序
 *   聚合（terms）  → 需要按值分组
 * 两者都读 doc_values（列式存储）。text 字段为了支持分词只建倒排索引、不建 doc_values，
 * 所以两个操作都会被 400 拒绝；换成 .keyword 子字段（或本来就是 keyword 的字段）就都成功。
 *
 * 本关把「拒绝」与「成功」并排放，并且把 400 的 root_cause 原文截出来 —— 只说「不能排序」
 * 而不给报错，学生下次遇到同样的 400 还是不认识。
 */
@Component
public class M52SortFielddata implements LevelModule {

    private static final String INDEX = SeedService.V2;

    /** 代表字段：两个 text、一个数值、一个 keyword，外加 text 的 keyword 子字段 */
    private static final List<String> SORT_FIELDS = List.of("title", "description", "price", "category", "title.keyword");

    private final EsClient es;

    public M52SortFielddata(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m5-2";
    }

    @Override
    public LevelResult run() throws Exception {
        Map<String, String> types = fieldTypes();

        // @focus-start 每个字段各做一次 sort 与一次 terms 聚合，成功记结果、失败记 root_cause
        List<List<Object>> rows = new ArrayList<>();
        Map<String, Object> detail = new LinkedHashMap<>();
        int sortOk = 0;
        int aggOk = 0;
        int textCount = 0;
        String textError = "";
        for (String field : SORT_FIELDS) {
            Outcome sort = trySort(field);
            Outcome agg = tryAgg(field);
            if (sort.ok()) sortOk++;
            if (agg.ok()) aggOk++;
            if ("text".equals(types.get(field))) textCount++;
            if (!sort.ok() && textError.isEmpty()) textError = sort.error();
            rows.add(TableData.row(
                    field,
                    types.getOrDefault(field, "—"),
                    sort.ok() ? "✓ " + sort.summary() : "✗ 400",
                    agg.ok() ? "✓ " + agg.summary() : "✗ 400",
                    sort.ok() && agg.ok() ? "有 doc_values，可排序可聚合"
                            : "只有倒排索引；改用 .keyword 或建 keyword 字段"));
            detail.put(field, Json.map(
                    "sort", sort.ok() ? sort.raw() : Json.map("error", sort.error()),
                    "agg", agg.ok() ? agg.raw() : Json.map("error", agg.error())));
        }
        // @focus-end

        return LevelResult.builder("title / description 是 text：排序和聚合都被 400 拒绝（Fielddata is disabled）；"
                        + "换成 title.keyword 与 price / category 就都成功 —— 卡住的不是「能不能读这个字段」，而是「有没有 doc_values」")
                .metrics(
                        new Metric("text 字段", textCount + " 个", "sort 与聚合都会失败"),
                        new Metric("可排序可聚合", (SORT_FIELDS.size() - textCount) + " 个", "keyword / 数值 / 日期"),
                        new Metric("sort 成功数", sortOk + " / " + SORT_FIELDS.size(), "其余被 400 拒绝"),
                        new Metric("terms 聚合成功数", aggOk + " / " + SORT_FIELDS.size(), "与排序同源：都要 doc_values"),
                        new Metric("text 排序的报错", "400 Fielddata is disabled",
                                textError.isEmpty() ? "本次未触发" : shorten(textError)))
                .table(new TableData(List.of("字段", "类型", "sort", "terms 聚合", "原因"), rows))
                .json(detail)
                .notes(
                        textError.isEmpty() ? "本关没有触发 fielddata 报错，请检查 mapping 是否被改过。"
                                : "报错原文（截断）：" + shorten(textError),
                        "doc_values 是 Lucene 的列式存储（磁盘上\"每列一个文件\"），排序、聚合、脚本访问都靠它；text 字段为了支持分词只建倒排索引，不建 doc_values。",
                        "不要为了排序去开 fielddata：它把词项加载进 JVM 堆，几十万文档就能吃掉几个 GB，且无法回收。正确做法是加 keyword 子字段。",
                        "数值/日期字段默认有 doc_values 且默认开启；keyword 也有。若确定某字段永不排序/聚合，可以 doc_values: false 省空间。",
                        "排序 + 分页组合还会带来\"深分页\"问题，下一模块专门讲。")
                .build();
    }

    private Map<String, String> fieldTypes() {
        JsonNode mapping = es.get("/" + INDEX + "/_mapping");
        JsonNode props = mapping.path(INDEX).path("mappings").path("properties");
        Map<String, String> types = new LinkedHashMap<>();
        props.fields().forEachRemaining(e -> {
            String type = e.getValue().hasNonNull("type") ? e.getValue().get("type").asText() : "object";
            types.put(e.getKey(), type);
            e.getValue().path("fields").fields().forEachRemaining(s ->
                    types.put(e.getKey() + "." + s.getKey(),
                            s.getValue().hasNonNull("type") ? s.getValue().get("type").asText() : "?"));
        });
        return types;
    }

    private Outcome trySort(String field) {
        long t0 = System.nanoTime();
        try {
            JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                    "size", 3,
                    "track_total_hits", false,
                    "_source", Json.list("title"),
                    "sort", Json.list(Json.map(field, "asc"))));
            double ms = Timing.round((System.nanoTime() - t0) / 1_000_000.0, 1);
            List<String> tops = new ArrayList<>();
            for (JsonNode h : r.path("hits").path("hits")) {
                tops.add(h.path("_source").path("title").asText());
            }
            return new Outcome(true, "", String.join(" · ", tops), ms, r);
        } catch (EsException e) {
            return new Outcome(false, message(e), "", 0, null);
        }
    }

    private Outcome tryAgg(String field) {
        long t0 = System.nanoTime();
        try {
            JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                    "size", 0,
                    "track_total_hits", false,
                    "aggs", Json.map("t", Json.map("terms", Json.map("field", field, "size", 3)))));
            double ms = Timing.round((System.nanoTime() - t0) / 1_000_000.0, 1);
            List<String> buckets = new ArrayList<>();
            for (JsonNode b : r.path("aggregations").path("t").path("buckets")) {
                JsonNode key = b.path("key");
                String text = key.isNumber()
                        ? String.valueOf(Math.round(key.asDouble() * 100) / 100.0)
                        : key.asText();
                buckets.add(text + "=" + b.path("doc_count").asLong());
            }
            return new Outcome(true, "", String.join(" · ", buckets), ms, r);
        } catch (EsException e) {
            return new Outcome(false, message(e), "", 0, null);
        }
    }

    private static String message(EsException e) {
        String m = e.getMessage() == null ? "400" : e.getMessage();
        int i = m.indexOf("Fielddata is disabled");
        if (i >= 0) return m.substring(i, Math.min(m.length(), i + 160));
        return shorten(m);
    }

    private static String shorten(String s) {
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }

    private record Outcome(boolean ok, String error, String summary, double ms, JsonNode raw) {
    }
}
