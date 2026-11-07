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
 *
 * 本关给三条容易被混在一起的数：三个请求各自的 total（高亮 / 折叠 / inner_hits）、
 * 一个「查得到但没有高亮」的样例（说明高亮依赖字段本身命中），
 * 以及品牌基数（cardinality）与折叠返回条数的差别 —— 折叠不是聚合。
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
        long hlTotal = hl.path("hits").path("total").path("value").asLong(0);

        // @focus-start 0 高亮样例：查询命中的是 description，而高亮只开在 title 上
        JsonNode zero = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 5,
                "track_total_hits", true,
                "query", Json.map("match", Json.map("description", "降噪")),
                "highlight", Json.map("fields", Json.map("title", Json.map("number_of_fragments", 1)))));
        int zeroHl = 0;
        for (JsonNode h : zero.path("hits").path("hits")) {
            if (!h.has("highlight")) zeroHl++;
        }
        long zeroTotal = zero.path("hits").path("total").path("value").asLong(0);
        // @focus-end

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
        long colTotal = col.path("hits").path("total").path("value").asLong(0);
        int returned = col.path("hits").path("hits").size();

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
        long innerTotal = inner.path("hits").path("total").path("value").asLong(0);

        // @focus-start 品牌基数据独立聚合算，别拿「返回了几条」当品牌数
        JsonNode card = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "aggs", Json.map("brands", Json.map("cardinality", Json.map("field", "brand")))));
        long brandCardinality = card.path("aggregations").path("brands").path("value").asLong(0);
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("highlight", highlighted + " / 5 条有高亮", fmt(hlTotal),
                truncate(sample, 50)));
        rows.add(TableData.row("highlight（只开 title，查的是 description）",
                zeroHl + " / 5 条无高亮", fmt(zeroTotal),
                "查到了但没有 <em>：高亮只对「该字段本身命中」生效"));
        rows.add(TableData.row("collapse(brand)", returned + " 条 · " + brands.size() + " 个品牌",
                fmt(colTotal), String.join(" / ", brands)));
        rows.add(TableData.row("collapse + inner_hits", groups + " 组各带 1 条", fmt(innerTotal),
                String.join(" / ", innerSamples)));
        rows.add(TableData.row("品牌基数（cardinality）", brandCardinality + " 个", "—",
                "独立聚合算出，与折叠返回条数不是一回事"));

        return LevelResult.builder("高亮只在「该字段本身命中」时出现（查 description 却只开 title 高亮 → "
                        + zeroHl + "/5 条无高亮）；collapse 把返回压到 " + returned
                        + " 条、但 total 仍是 " + fmt(colTotal) + "；品牌基数是 " + brandCardinality
                        + " 个 —— 折叠只影响返回，既不改统计，也不等于聚合")
                .metrics(
                        new Metric("高亮覆盖", highlighted + " / 5", "命中的词被包上 <em>"),
                        new Metric("0 高亮样例", zeroHl + " / 5", "查 description、只开 title 高亮"),
                        new Metric("三个请求的 total", fmt(hlTotal) + " / " + fmt(colTotal) + " / " + fmt(innerTotal),
                                "高亮 / 折叠 / inner_hits 各自独立"),
                        new Metric("折叠后返回", returned + " 条", brands.size() + " 个品牌（只是这 5 条里的）"),
                        new Metric("品牌基数", brandCardinality + " 个", "cardinality 聚合，独立算"),
                        new Metric("inner_hits 组数", groups, "每组取最贵的一条"))
                .table(TableData.of(List.of("查询", "返回", "total", "示例 / 说明"), rows))
                .json(Json.map("highlightSample", sample, "brands", brands,
                        "totals", Json.map("highlight", hlTotal, "collapse", colTotal,
                                "innerHits", innerTotal, "zeroHighlightTotal", zeroTotal),
                        "brandCardinality", brandCardinality))
                .notes(
                        "三个请求的 total 要分开看：高亮查询带 match 条件、折叠与 inner_hits 是 match_all，本来就不该相等；把同一个数写进三行是错的",
                        "高亮依赖「字段本身命中」：查 description 却只开 title 高亮，就会拿到没有 <em> 的结果 —— 这不是 bug，是配置问题",
                        "collapse 只能用在 keyword 或数值字段上，且是「按字段去重」而不是「分组统计」；品牌真实数量要用 cardinality 聚合算，本关是 "
                                + brandCardinality + " 个，而折叠返回只有 " + returned + " 条",
                        "折叠后的 total 仍是全部命中数；如果想让分页与折叠一致，需要自己做游标或改用聚合",
                        "highlight 的代价是每条结果都要重新分析一次字段，结果集大时不要对长文本开很多 fragment")
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
