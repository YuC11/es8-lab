package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M5-1 terms 聚合：doc_count 为什么是"近似"的
 *
 * terms 聚合是分布式近似算法：每个分片各自取 top N 上报，协调节点合并。
 * 所以 size 越大、分片越多，误差项 doc_count_error_upper_bound 越大。
 */
@Component
public class M51TermsAccuracy implements LevelModule {

    private static final String INDEX = SeedService.V2;

    private final EsClient es;

    public M51TermsAccuracy(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m5-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 两个 terms 聚合：低基数（12 个类目）vs 高基数（30 个品牌），size 都只取 5
        JsonNode res = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0,
                "aggs", Json.map(
                        "by_category", Json.map("terms", Json.map("field", "category", "size", 5)),
                        "by_brand", Json.map("terms", Json.map("field", "brand", "size", 5)),
                        "brand_exact", Json.map("terms", Json.map("field", "brand", "size", 5, "shard_size", 100)))));
        // @focus-end

        JsonNode cat = res.path("aggregations").path("by_category");
        JsonNode brand = res.path("aggregations").path("by_brand");
        JsonNode brandExact = res.path("aggregations").path("brand_exact");

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("category（12 个值）",
                cat.path("buckets").size(),
                cat.path("sum_other_doc_count").asLong(),
                cat.path("doc_count_error_upper_bound").asLong()));
        rows.add(TableData.row("brand size=5（默认 shard_size）",
                brand.path("buckets").size(),
                brand.path("sum_other_doc_count").asLong(),
                brand.path("doc_count_error_upper_bound").asLong()));
        rows.add(TableData.row("brand size=5（shard_size=100）",
                brandExact.path("buckets").size(),
                brandExact.path("sum_other_doc_count").asLong(),
                brandExact.path("doc_count_error_upper_bound").asLong()));

        JsonNode total = es.get("/" + INDEX + "/_count");

        List<BarChartData.Item> items = new ArrayList<>();
        for (JsonNode b : cat.path("buckets")) {
            items.add(new BarChartData.Item(b.path("key").asText(), b.path("doc_count").asDouble()));
        }

        long catError = cat.path("doc_count_error_upper_bound").asLong();
        long brandError = brand.path("doc_count_error_upper_bound").asLong();

        return LevelResult.builder("类目聚合误差为 " + catError + "，品牌聚合误差为 " + brandError
                        + "——分片各自 top N 合并时，被截断的候选会造成计数偏差")
                .metrics(
                        new Metric("文档总数", total.path("count").asLong(), null),
                        new Metric("类目桶数", cat.path("buckets").size(), "取 size=5，实际 12 个类目"),
                        new Metric("other 文档数", cat.path("sum_other_doc_count").asLong(),
                                "未进入 top5 的桶所覆盖文档"),
                        new Metric("误差上界", "类目 " + catError + " / 品牌 " + brandError, null))
                .table(new TableData(
                        List.of("聚合", "返回桶数", "sum_other_doc_count", "doc_count_error_upper_bound"),
                        rows))
                .chart(BarChartData.of("文档数", "Top 5 类目（Zipf 分布，头部集中）",
                        items.toArray(new BarChartData.Item[0])))
                .json(Json.map(
                        "by_category", cat,
                        "by_brand", brand,
                        "brand_exact", brandExact))
                .notes(
                        "doc_count_error_upper_bound 是\"最坏情况下某个桶的计数可能少算了多少\"，不是随机误差；只要它不为 0，就不要把结果当精确值用。",
                        "想降低误差：调大 shard_size（默认 size*1.5+10）、减少分片数、或对精度要求极高的报表改用 composite aggregation 分页遍历。",
                        "sum_other_doc_count 是\"没被返回的桶里还有多少文档\"，配合 size 判断是否有长尾被忽略。",
                        "terms 聚合只能用于 keyword / 数值 / 布尔；对 text 字段聚合需要开启 fielddata（非常吃堆内存，见下一关）。")
                .build();
    }
}
