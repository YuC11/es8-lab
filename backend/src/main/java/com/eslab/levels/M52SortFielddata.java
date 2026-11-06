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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M5-2 排序为什么总让你加 .keyword
 *
 * 排序需要"按文档取字段值"，这要求列式存储（doc_values）。
 * text 字段只有倒排索引（词项→文档），没有 doc_values，所以不能排序/聚合。
 */
@Component
public class M52SortFielddata implements LevelModule {

    private static final String INDEX = SeedService.V2;

    private static final List<String> MATRIX_FIELDS = List.of(
            "sku", "title", "description", "category", "price", "status", "tags", "created_at");

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
        // @focus-start 三种排序目标：数值字段 / text 字段 / keyword 子字段
        SortOutcome byPrice = trySort(Json.list(Json.map("price", "asc")));
        SortOutcome byText = trySort(Json.list(Json.map("title", "asc")));
        SortOutcome byKeyword = trySort(Json.list(Json.map("title.keyword", "asc")));
        // @focus-end

        // 字段能力矩阵：从 mapping 直接读出每个字段有没有 doc_values
        JsonNode mapping = es.get("/" + INDEX + "/_mapping");
        JsonNode props = mapping.path(INDEX).path("mappings").path("properties");

        List<List<Object>> rows = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = props.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> e = fields.next();
            if (!MATRIX_FIELDS.contains(e.getKey())) continue;

            JsonNode def = e.getValue();
            String type = def.hasNonNull("type") ? def.get("type").asText() : "object";
            boolean sortable = !"text".equals(type);
            rows.add(TableData.row(
                    e.getKey(),
                    type,
                    sortable ? "✓ 有 doc_values" : "✗ 只有倒排索引",
                    sortable ? "可排序 / 可聚合" : "只能全文检索；需 .keyword 子字段"));
        }

        Map<String, Object> json = new LinkedHashMap<>();
        if (!byPrice.ok()) json.put("priceError", byPrice.error());
        if (!byText.ok()) json.put("textError", byText.error());
        json.put("tops", byPrice.tops());

        List<String> notes = new ArrayList<>();
        if (!byText.ok()) notes.add("报错原文：" + byText.error());
        notes.add("doc_values 是 Lucene 的列式存储（磁盘上的\"每列一个文件\"），排序、聚合、脚本访问都靠它；text 字段为了支持分词只建倒排索引，不建 doc_values。");
        notes.add("不要为了排序去开 fielddata：它把词项加载进 JVM 堆，几十万文档就能吃掉几个 GB，且无法回收。正确做法是加 keyword 子字段。");
        notes.add("数值/日期字段默认有 doc_values 且默认开启；keyword 也有。若确定某字段永不排序/聚合，可以 doc_values: false 省空间。");
        notes.add("排序 + 分页组合还会带来\"深分页\"问题，下一模块专门讲。");

        return LevelResult.builder(byText.ok()
                        ? "text 字段排序居然成功了（不符合预期，请检查 mapping）"
                        : "按 title（text）排序直接 400：Fielddata is disabled；改按 title.keyword 就正常——这就是\"排序/聚合要用 keyword\"的底层原因")
                .metrics(
                        new Metric("sort price", byPrice.ok() ? "✓ 成功" : "✗ 失败", num(byPrice.ms()) + "ms"),
                        new Metric("sort title", byText.ok() ? "✓ 成功" : "✗ 400", num(byText.ms()) + "ms"),
                        new Metric("sort title.keyword", byKeyword.ok() ? "✓ 成功" : "✗ 失败", num(byKeyword.ms()) + "ms"),
                        new Metric("text 字段能否聚合", "不能（默认）", "开启 fielddata 会吃堆内存"))
                .table(new TableData(List.of("字段", "类型", "列式存储", "能力"), rows))
                .json(json)
                .notes(notes)
                .build();
    }

    private SortOutcome trySort(List<Object> sort) {
        long t0 = System.nanoTime();
        try {
            JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                    "size", 3,
                    "_source", Json.list("title", "price"),
                    "sort", sort));
            double ms = Timing.round((System.nanoTime() - t0) / 1_000_000.0, 1);

            List<String> tops = new ArrayList<>();
            for (JsonNode h : r.path("hits").path("hits")) {
                JsonNode src = h.path("_source");
                tops.add(src.path("title").asText() + " ¥" + num(src.path("price").asDouble()));
            }
            return new SortOutcome(true, ms, null, tops);
        } catch (EsException e) {
            double ms = Timing.round((System.nanoTime() - t0) / 1_000_000.0, 1);
            return new SortOutcome(false, ms, e.getMessage(), List.of());
        }
    }

    /** 复刻 JS 数字的默认字符串形式：整数不带 ".0" */
    private static String num(double v) {
        if (!Double.isInfinite(v) && !Double.isNaN(v) && v == Math.rint(v)) {
            return String.valueOf((long) v);
        }
        return String.valueOf(v);
    }

    private record SortOutcome(boolean ok, double ms, String error, List<String> tops) {
    }
}
