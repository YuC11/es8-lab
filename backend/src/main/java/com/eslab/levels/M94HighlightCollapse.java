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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * m9-4：高亮与折叠。
 *
 * 这两个能力都在回答「结果怎么展示」：
 * highlight 告诉用户「为什么这条被搜到」，collapse 让同一品牌只出现一次。
 */
@Component
public class M94HighlightCollapse implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;

    private final EsClient es;
    private final LabData lab;

    public M94HighlightCollapse(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-4";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.ensureSearch();

        // @focus-start 高亮：让 ES 把命中的词包上标记，前端直接渲染
        JsonNode hl = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 5,
                "track_total_hits", true,
                "query", Json.map("match", Json.map("title", "蓝牙耳机")),
                "highlight", Json.map("fields", Json.map(
                        "title", Json.map("number_of_fragments", 1),
                        "description", Json.map("fragment_size", 40)))));
        // @focus-end

        int highlighted = 0;
        String sample = "";
        for (JsonNode h : hl.path("hits").path("hits")) {
            if (h.has("highlight")) {
                highlighted++;
                if (sample.isEmpty()) {
                    JsonNode t = h.path("highlight").path("title");
                    sample = t.isArray() && !t.isEmpty() ? t.get(0).asText() : "";
                }
            }
        }

        // @focus-start collapse：同一个品牌只保留一条（按销量最高的那条）
        JsonNode col = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 5,
                "track_total_hits", true,
                "query", Json.map("match_all", Json.map()),
                "collapse", Json.map("field", "brand"),
                "sort", List.of(Json.map("sales", Json.map("order", "desc"))),
                "_source", List.of("title", "brand", "sales", "price")));
        // @focus-end

        Set<String> brands = new LinkedHashSet<>();
        for (JsonNode h : col.path("hits").path("hits")) {
            brands.add(h.path("_source").path("brand").asText("?"));
        }

        // @focus-start inner_hits：折叠后每组还能带一条代表文档（这里取每组最贵的）
        JsonNode inner = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 3,
                "track_total_hits", true,
                "query", Json.map("match_all", Json.map()),
                "collapse", Json.map(
                        "field", "brand",
                        "inner_hits", Json.map(
                                "name", "top_by_price",
                                "size", 1,
                                "sort", List.of(Json.map("price", Json.map("order", "desc"))))),
                "sort", List.of(Json.map("sales", Json.map("order", "desc"))),
                "_source", List.of("title", "brand")));
        // @focus-end

        int groups = 0;
        List<String> innerSamples = new ArrayList<>();
        for (JsonNode h : inner.path("hits").path("hits")) {
            JsonNode ih = h.path("inner_hits").path("top_by_price").path("hits").path("hits");
            if (ih.isArray() && !ih.isEmpty()) {
                groups++;
                if (innerSamples.size() < 3) {
                    innerSamples.add(h.path("_source").path("brand").asText("?")
                            + " · " + ih.get(0).path("_source").path("title").asText("—"));
                }
            }
        }

        long total = col.path("hits").path("total").path("value").asLong(0);
        int returned = col.path("hits").path("hits").size();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("highlight", highlighted + " / 5 条有高亮", String.valueOf(total),
                truncate(sample, 60)));
        rows.add(TableData.row("collapse(brand)", returned + " 条 · " + brands.size() + " 个品牌",
                fmt(total), String.join(" / ", brands)));
        rows.add(TableData.row("collapse + inner_hits", groups + " 组各带 1 条", fmt(total),
                String.join(" / ", innerSamples)));

        return LevelResult.builder("高亮让 5 条结果都能说明「为什么被搜到」；collapse 把返回条数压到 "
                        + returned + " 条（每品牌一条），但 total 仍然是 " + fmt(total) + " —— 折叠只影响返回，不影响统计")
                .metrics(
                        new Metric("高亮覆盖", highlighted + " / 5", "命中的词被包上 <em>"),
                        new Metric("折叠后返回", returned + " 条", brands.size() + " 个品牌"),
                        new Metric("total 是否变化", fmt(total), "折叠不影响命中总数"),
                        new Metric("inner_hits 组数", groups, "每组取最贵的一条"))
                .table(TableData.of(List.of("查询", "返回", "total", "示例"), rows))
                .json(Map.of("highlightSample", sample, "brands", brands))
                .notes(
                        "collapse 只能用在 keyword 或数值字段上，且是「按字段去重」而不是「分组统计」—— 要统计每组数量请用 terms 聚合",
                        "折叠后的 total 仍是全部命中数；如果想让分页与折叠一致，需要自己做游标或改用聚合",
                        "highlight 的代价是每条结果都要重新分析一次字段，结果集大时不要对长文本开 fragment 很多的高亮")
                .build();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.isEmpty()) return "—";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
