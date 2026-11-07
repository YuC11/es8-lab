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
import java.util.Locale;

/**
 * M5-1 terms 聚合：doc_count 为什么是"近似"的
 *
 * terms 聚合是分布式近似算法：每个分片各自取 top N 上报，协调节点合并。
 * 所以 size 越大、分片越多，误差项 doc_count_error_upper_bound 越大。
 *
 * 本关给两个可核对的交叉验证：
 *   ① 桶内 doc_count 必须等于同条件的 filter 查询计数（拿真实值回查聚合并值）；
 *   ② 各桶 doc_count 之和 + sum_other_doc_count 必须等于精确 total。
 * 误差上界不为 0 时，第 ① 步仍可能相等 —— 那正说明「上界 ≠ 实际误差」。
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
                "track_total_hits", true,
                "aggs", Json.map(
                        "by_category", Json.map("terms", Json.map("field", "category", "size", 5)),
                        "by_brand", Json.map("terms", Json.map("field", "brand", "size", 5)),
                        "brand_exact", Json.map("terms", Json.map("field", "brand", "size", 5, "shard_size", 100)))));
        // @focus-end

        JsonNode cat = res.path("aggregations").path("by_category");
        JsonNode brand = res.path("aggregations").path("by_brand");
        JsonNode brandExact = res.path("aggregations").path("brand_exact");
        long exactTotal = res.path("hits").path("total").path("value").asLong();

        // @focus-start 交叉验证：拿桶里的 key 再查一次精确计数
        JsonNode catTop = cat.path("buckets").path(0);
        JsonNode brandTop = brand.path("buckets").path(0);
        long catTopKeyCount = catTop.path("doc_count").asLong();
        long brandTopKeyCount = brandTop.path("doc_count").asLong();
        long catFiltered = countByTerm("category", catTop.path("key").asText());
        long brandFiltered = countByTerm("brand", brandTop.path("key").asText());
        // @focus-end

        long catBucketSum = sumBuckets(cat);
        long brandBucketSum = sumBuckets(brand);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("category（低基数）",
                cat.path("buckets").size(),
                cat.path("sum_other_doc_count").asLong(),
                cat.path("doc_count_error_upper_bound").asLong(),
                catTop.path("key").asText() + " " + catTopKeyCount + " / 过滤查询 " + catFiltered,
                catTopKeyCount == catFiltered ? "一致" : "不一致"));
        rows.add(TableData.row("brand size=5（默认 shard_size）",
                brand.path("buckets").size(),
                brand.path("sum_other_doc_count").asLong(),
                brand.path("doc_count_error_upper_bound").asLong(),
                brandTop.path("key").asText() + " " + brandTopKeyCount + " / 过滤查询 " + brandFiltered,
                brandTopKeyCount == brandFiltered ? "一致" : "不一致"));
        rows.add(TableData.row("brand size=5（shard_size=100）",
                brandExact.path("buckets").size(),
                brandExact.path("sum_other_doc_count").asLong(),
                brandExact.path("doc_count_error_upper_bound").asLong(),
                "（本条只用于看误差上界）",
                "桶与计数通常不变"));

        JsonNode total = es.get("/" + INDEX + "/_count");

        List<BarChartData.Item> items = new ArrayList<>();
        for (JsonNode b : cat.path("buckets")) {
            items.add(new BarChartData.Item(b.path("key").asText(), b.path("doc_count").asDouble()));
        }

        long catError = cat.path("doc_count_error_upper_bound").asLong();
        long brandError = brand.path("doc_count_error_upper_bound").asLong();
        long brandExactError = brandExact.path("doc_count_error_upper_bound").asLong();
        boolean catBalanced = catBucketSum + cat.path("sum_other_doc_count").asLong() == exactTotal;
        boolean brandBalanced = brandBucketSum + brand.path("sum_other_doc_count").asLong() == exactTotal;

        return LevelResult.builder("类目聚合误差上界 " + catError + "，品牌 " + brandError
                        + "，把 shard_size 调到 100 后降到 " + brandExactError
                        + " —— 但两次返回的桶与计数完全一样：上界是「最坏情况」，不是「一定错了」")
                .metrics(
                        new Metric("精确 total", String.format(Locale.ROOT, "%,d", exactTotal), "track_total_hits:true"),
                        new Metric("类目桶数", cat.path("buckets").size(), "取 size=5，实际 12 个类目"),
                        new Metric("other 文档数", cat.path("sum_other_doc_count").asLong(),
                                "未进入 top5 的桶所覆盖文档"),
                        new Metric("误差上界", "类目 " + catError + " / 品牌 " + brandError
                                        + " / shard_size=100 时 " + brandExactError,
                                "不为 0 不等于结果错了"),
                        new Metric("桶和 + other = total", (catBalanced ? "类目 ✓" : "类目 ✗")
                                        + " / " + (brandBalanced ? "品牌 ✓" : "品牌 ✗"),
                                "5 个桶之和 " + catBucketSum + " + other " + cat.path("sum_other_doc_count").asLong()
                                        + " = " + exactTotal),
                        new Metric("Top1 桶与过滤查询", (catTopKeyCount == catFiltered ? "类目一致" : "类目不一致")
                                        + " / " + (brandTopKeyCount == brandFiltered ? "品牌一致" : "品牌不一致"),
                                "拿桶的 key 再查一次 _count"))
                .table(new TableData(
                        List.of("聚合", "返回桶数", "sum_other_doc_count", "误差上界", "Top1 桶 / 过滤精确值", "核对"),
                        rows))
                .chart(BarChartData.of("文档数", "Top 5 类目（Zipf 分布，头部集中）",
                        items.toArray(new BarChartData.Item[0])))
                .json(Json.map(
                        "by_category", cat,
                        "by_brand", brand,
                        "brand_exact", brandExact,
                        "crossCheck", Json.map(
                                "categoryTop", catTopKeyCount, "categoryFiltered", catFiltered,
                                "brandTop", brandTopKeyCount, "brandFiltered", brandFiltered,
                                "categoryBalanced", catBalanced, "brandBalanced", brandBalanced)))
                .notes(
                        "doc_count_error_upper_bound 是\"最坏情况下某个桶的计数可能少算了多少\"，不是随机误差；只要它不为 0，就不要把结果当精确值用。",
                        "但它也不等于「一定有误差」：本关品牌聚合上界 " + brandError + "，而 Top1 桶与过滤查询的结果"
                                + (brandTopKeyCount == brandFiltered ? "完全一致" : "并不一致") + "。上界是保守估计。",
                        "两个可自查的等式：桶 doc_count 之和 + sum_other_doc_count = 精确 total；任一桶的 doc_count = 同条件 filter 查询的计数。",
                        "想降低误差：调大 shard_size（默认 size*1.5+10）、减少分片数、或对精度要求极高的报表改用 composite aggregation 分页遍历。",
                        "sum_other_doc_count 是\"没被返回的桶里还有多少文档\"，配合 size 判断是否有长尾被忽略。",
                        "terms 聚合只能用于 keyword / 数值 / 布尔；对 text 字段聚合需要开启 fielddata（非常吃堆内存，见下一关）。")
                .build();
    }

    private long countByTerm(String field, String value) {
        JsonNode r = es.post("/" + INDEX + "/_count",
                Json.map("query", Json.map("term", Json.map(field, value))));
        return r.path("count").asLong(0);
    }

    private static long sumBuckets(JsonNode agg) {
        long sum = 0;
        for (JsonNode b : agg.path("buckets")) sum += b.path("doc_count").asLong();
        return sum;
    }
}
