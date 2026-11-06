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
 * 响应体也是固定骨架。本关跑四组对照，把每个字段对应回「它由谁控制」。
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
        // @focus-start 同一份数据、同一个查询，只改 size 与 track_total_hits
        JsonNode byDefault = es.post("/" + INDEX + "/_search", Json.map("size", 0));
        JsonNode exactTotal = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true));
        JsonNode withDocs = es.post("/" + INDEX + "/_search",
                Json.map("size", 2, "track_total_hits", true));
        JsonNode projected = es.post("/" + INDEX + "/_search",
                Json.map("size", 1, "_source", Json.list("title", "price")));
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

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("took", "ES 内部耗时（毫秒），不含网络往返",
                exactTotal.path("took").asLong() + " ms"));
        rows.add(TableData.row("timed_out", "是否因为超时提前返回", exactTotal.path("timed_out").asBoolean()));
        rows.add(TableData.row("_shards.total", "本次检索打到的分片副本数", exactTotal.path("_shards").path("total").asInt()));
        rows.add(TableData.row("hits.total.value", "命中总数", String.format(Locale.ROOT, "%,d", exact)));
        rows.add(TableData.row("hits.total.relation", "eq = 精确值；gte = 只是下限", exactRelation));
        rows.add(TableData.row("hits.max_score", "本页命中的最高分", maxScore));
        rows.add(TableData.row("hits.hits[]._score", "单条文档的相关性分", firstScore));
        rows.add(TableData.row("hits.hits[]._source", "原始文档（可被 _source 裁剪）",
                String.join(", ", projectedFields)));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("默认(size:0)", byDefault);
        json.put("精确总数(size:0)", exactTotal);
        json.put("返回文档(size:2)", withDocs);
        json.put("裁剪字段(size:1)", projected);

        return LevelResult.builder("hits.total 默认只保证 " + String.format(Locale.ROOT, "%,d", defaultTotal)
                        + " 条（relation: " + defaultRelation + "），要精确就写 track_total_hits: true；"
                        + "max_score 只在真的返回了文档时才有值 —— 它显示 null 不是查询失败")
                .metrics(
                        new Metric("默认 total", String.format(Locale.ROOT, "%,d / %s", defaultTotal, defaultRelation),
                                "默认只保证 10000，relation 告诉你这是下限"),
                        new Metric("精确 total", String.format(Locale.ROOT, "%,d / %s", exact, exactRelation),
                                "track_total_hits: true 的代价是精确计数"),
                        new Metric("size:0 的 max_score", "null", "没有 hits 返回，就不算分"),
                        new Metric("size:2 的 max_score", maxScore, "有文档返回时才有分数"),
                        new Metric("_source 裁剪后字段数", projectedFields.size(),
                                projectedFields.isEmpty() ? "—" : String.join(", ", projectedFields)))
                .table(new TableData(List.of("响应路径", "含义", "本次实测"), rows))
                .json(json)
                .notes(
                        "请求体的顶层只有两类键：query 决定「查什么」，其余（size/from/_source/sort/aggs/track_total_hits）决定「怎么给」。",
                        "size 默认 10：不写 size 就拿不到超过 10 条；size: 0 表示「不要文档，只要统计」，聚合与计数都用它。",
                        "track_total_hits 默认 10000：命中数超过一万时 value 会被截在 10000、relation 变成 gte —— 你的数据是 "
                                + String.format(Locale.ROOT, "%,d", exact) + " 条，所以这个差别一眼可见。",
                        "max_score 为 null 有两种原因：size: 0 没返回文档，或者查询本身不产生分数。它不是错误。",
                        "_source 可以只取需要的字段，字段多时能显著降低网络与序列化开销。")
                .build();
    }
}
