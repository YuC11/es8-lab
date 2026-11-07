package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
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
import java.util.Locale;
import java.util.Map;

/**
 * M4-1 一个 _search 请求由什么组成
 *
 * 请求体里只有 query 是「查什么」，其余都在回答「怎么给」：
 *   size / from          返回多少条、从第几条开始
 *   track_total_hits     要不要精确总数（默认只保证 10000）
 *   _source              返回哪些字段
 *   sort / aggs          换一种目的：排序、统计
 *
 * 响应体也是固定骨架。本关跑六次请求，把每个响应字段对应回「它由谁控制」，
 * 并在表格第 4 列标明这个值取自哪一次请求 —— 否则同一张表里混着多条请求的数，
 * 读的人无从判断它是不是可比的。
 */
@Component
public class M41SearchAnatomy implements LevelModule {

    private static final String INDEX = SeedService.V2;

    private final EsClient es;

    public M41SearchAnatomy(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 同一个查询，只改「怎么给」：要统计 / 要精确数 / 要文档 / 裁字段 / 排序 / 聚合
        JsonNode byDefault = es.post("/" + INDEX + "/_search", Json.map("size", 0));
        JsonNode exactTotal = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true));
        JsonNode withDocs = es.post("/" + INDEX + "/_search",
                Json.map("size", 2, "track_total_hits", true));
        JsonNode projected = es.post("/" + INDEX + "/_search",
                Json.map("size", 1, "track_total_hits", true, "_source", Json.list("title", "price")));
        JsonNode sorted = es.post("/" + INDEX + "/_search", Json.map(
                "size", 2, "track_total_hits", false,
                "_source", Json.list("title", "price"),
                "sort", Json.list(Json.map("price", "asc"), Json.map("sku", "asc"))));
        JsonNode aggregated = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0, "track_total_hits", false,
                "aggs", Json.map("by_category", Json.map("terms",
                        Json.map("field", "category", "size", 3)))));
        // @focus-end

        long defaultTotal = byDefault.path("hits").path("total").path("value").asLong();
        String defaultRelation = byDefault.path("hits").path("total").path("relation").asText();
        long exact = exactTotal.path("hits").path("total").path("value").asLong();
        String exactRelation = exactTotal.path("hits").path("total").path("relation").asText();

        JsonNode hitsNode = withDocs.path("hits");
        JsonNode maxScoreNode = hitsNode.path("max_score");
        String maxScore = maxScoreNode.isNumber()
                ? String.format(Locale.ROOT, "%.3f", maxScoreNode.asDouble())
                : "null";
        String firstScore = "—";
        if (hitsNode.path("hits").size() > 0) {
            JsonNode s = hitsNode.path("hits").get(0).path("_score");
            firstScore = s.isNumber() ? String.format(Locale.ROOT, "%.3f", s.asDouble()) : "null";
        }

        // _source 投影后，单条文档只剩指定的两个字段
        List<String> projectedFields = new ArrayList<>();
        JsonNode src = projected.path("hits").path("hits").path(0).path("_source");
        src.fieldNames().forEachRemaining(projectedFields::add);

        // 显式 sort 之后 ES 不再打相关性分，max_score 直接变成 null —— 这不是出错
        JsonNode sortedHits = sorted.path("hits");
        String sortedMaxScore = sortedHits.path("max_score").isNumber()
                ? String.format(Locale.ROOT, "%.3f", sortedHits.path("max_score").asDouble())
                : "null";
        List<String> sortedTops = new ArrayList<>();
        List<String> sortValues = new ArrayList<>();
        for (JsonNode h : sortedHits.path("hits")) {
            sortedTops.add(h.path("_source").path("title").asText());
            JsonNode sv = h.path("sort");
            if (sv.isArray() && sv.size() >= 2) {
                sortValues.add(sv.get(0).asText() + " / " + sv.get(1).asText());
            }
        }

        JsonNode bucketNode = aggregated.path("aggregations").path("by_category");
        List<String> aggBuckets = new ArrayList<>();
        for (JsonNode b : bucketNode.path("buckets")) {
            aggBuckets.add(b.path("key").asText() + "=" + String.format(Locale.ROOT, "%,d",
                    b.path("doc_count").asLong()));
        }
        long aggOther = bucketNode.path("sum_other_doc_count").asLong(0);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("took", "ES 内部耗时（毫秒），不含网络往返",
                exactTotal.path("took").asLong() + " ms", "精确总数"));
        rows.add(TableData.row("timed_out", "是否因为超时提前返回",
                exactTotal.path("timed_out").asBoolean(), "精确总数"));
        rows.add(TableData.row("_shards.total", "本次检索打到的分片副本数",
                exactTotal.path("_shards").path("total").asInt(), "精确总数"));
        rows.add(TableData.row("hits.total.value", "命中总数",
                String.format(Locale.ROOT, "%,d", exact), "精确总数"));
        rows.add(TableData.row("hits.total.relation", "eq = 精确值；gte = 只是下限",
                exactRelation, "精确总数"));
        rows.add(TableData.row("hits.max_score", "本页命中的最高分",
                maxScore, "返回文档"));
        rows.add(TableData.row("hits.hits[]._score", "单条文档的相关性分",
                firstScore, "返回文档"));
        rows.add(TableData.row("hits.hits[]._source", "原始文档（可被 _source 裁剪）",
                String.join(", ", projectedFields), "裁剪字段"));
        rows.add(TableData.row("hits.hits[].sort", "显式排序后返回的排序值（此时 max_score = "
                + sortedMaxScore + "）",
                String.join(" · ", sortValues), "排序"));
        rows.add(TableData.row("aggregations.by_category", "聚合结果：桶 + 未返回文档数",
                String.join(" · ", aggBuckets) + (aggOther > 0 ? "，其余 " + String.format(Locale.ROOT, "%,d", aggOther) + " 条" : ""),
                "聚合"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("默认(size:0)", byDefault);
        json.put("精确总数(size:0)", exactTotal);
        json.put("返回文档(size:2)", withDocs);
        json.put("裁剪字段(size:1)", projected);
        json.put("排序(sort)", sorted);
        json.put("聚合(aggs)", aggregated);

        return LevelResult.builder("hits.total 默认只保证 " + String.format(Locale.ROOT, "%,d", defaultTotal)
                        + " 条（relation: " + defaultRelation + "），要精确就写 track_total_hits: true；"
                        + "max_score 为 null 有两种来源 —— size:0 没返回文档，或显式 sort 后不再打分（本关两次都出现了）")
                .metrics(
                        new Metric("默认 total", String.format(Locale.ROOT, "%,d / %s", defaultTotal, defaultRelation),
                                "默认只保证 10000，relation 告诉你这是下限"),
                        new Metric("精确 total", String.format(Locale.ROOT, "%,d / %s", exact, exactRelation),
                                "track_total_hits: true 的代价是精确计数"),
                        new Metric("size:2 的 max_score", maxScore, "有文档返回时才有分数"),
                        new Metric("显式 sort 的 max_score", sortedMaxScore, "排序请求不打分，直接是 null"),
                        new Metric("_source 裁剪后字段数", projectedFields.size(),
                                projectedFields.isEmpty() ? "—" : String.join(", ", projectedFields)),
                        new Metric("聚合首桶", aggBuckets.isEmpty() ? "—" : aggBuckets.get(0),
                                "size:0 + aggs，只拿统计不拿文档"))
                .table(new TableData(List.of("响应路径", "含义", "本次实测", "这个值来自"), rows))
                .json(json)
                .notes(
                        "请求体的顶层只有两类键：query 决定「查什么」，其余（size/from/_source/sort/aggs/track_total_hits）决定「怎么给」。",
                        "size 默认 10：不写 size 就拿不到超过 10 条；size: 0 表示「不要文档，只要统计」，聚合与计数都用它。",
                        "track_total_hits 默认 10000：命中数超过一万时 value 会被截在 10000、relation 变成 gte —— 你的数据是 "
                                + String.format(Locale.ROOT, "%,d", exact) + " 条，所以这个差别一眼可见。",
                        "max_score 为 null 有三种来源，都不是错误：size: 0 没返回文档、显式 sort 后不再相关性打分、查询本身不产生分数。",
                        "sort 与 aggs 只改「怎么给」：前者换返回顺序（并顺手关掉打分），后者换返回内容（桶代替文档）。聚合不需要 size:0 也能跑，但 size:0 能省掉取 _source 的开销。",
                        "_source 可以只取需要的字段，字段多时能显著降低网络与序列化开销。")
                .build();
    }
}
