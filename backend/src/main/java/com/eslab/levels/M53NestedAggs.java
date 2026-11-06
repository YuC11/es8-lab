package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M5-3 嵌套聚合：把「每个类目下卖得最好的品牌」一次算出来
 *
 * 聚合是树：父桶的每个 bucket 上再挂子聚合。
 * 关键认知：子聚合的输入是「父桶内的文档子集」，不是全量数据。
 */
@Component
public class M53NestedAggs implements LevelModule {

    private static final String V2 = "eslab-products-v2";

    private final EsClient es;

    public M53NestedAggs(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m5-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 类目 → 品牌 → 价格统计，再单独挂一个按月的时间直方图
        JsonNode res = es.post("/" + V2 + "/_search", Json.map(
                "size", 0,
                "query", Json.map("term", Json.map("status", 1)),
                "aggs", Json.map(
                        "by_category", Json.map(
                                "terms", Json.map("field", "category", "size", 4),
                                "aggs", Json.map(
                                        "top_brands", Json.map(
                                                "terms", Json.map("field", "brand", "size", 2),
                                                "aggs", Json.map(
                                                        "price_stats", Json.map("stats", Json.map("field", "price")))),
                                        "price_p95", Json.map(
                                                "percentiles", Json.map("field", "price", "percents", Json.list(50, 95))))),
                        "by_month", Json.map(
                                "date_histogram", Json.map(
                                        "field", "created_at",
                                        "calendar_interval", "month",
                                        "min_doc_count", 1),
                                "aggs", Json.map(
                                        "avg_price", Json.map("avg", Json.map("field", "price")))))));
        // @focus-end

        JsonNode categoryBuckets = res.path("aggregations").path("by_category").path("buckets");

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode c : categoryBuckets) {
            for (JsonNode b : c.path("top_brands").path("buckets")) {
                rows.add(TableData.row(
                        c.path("key").asText(),
                        c.path("doc_count").asLong(),
                        b.path("key").asText(),
                        b.path("doc_count").asLong(),
                        Timing.round(b.path("price_stats").path("avg").asDouble(), 2),
                        Timing.round(b.path("price_stats").path("max").asDouble(), 2)));
            }
        }

        List<BarChartData.Item> months = new ArrayList<>();
        for (JsonNode b : res.path("aggregations").path("by_month").path("buckets")) {
            String key = b.path("key_as_string").asText();
            JsonNode avg = b.path("avg_price").path("value");
            months.add(new BarChartData.Item(
                    key.length() > 7 ? key.substring(0, 7) : key,
                    avg.isNumber() ? Math.round(avg.asDouble()) : 0));
        }
        List<BarChartData.Item> recent = months.subList(Math.max(0, months.size() - 12), months.size());

        List<Object> p95Rows = new ArrayList<>();
        for (JsonNode b : categoryBuckets) {
            JsonNode values = b.path("price_p95").path("values");
            p95Rows.add(Json.map(
                    "key", b.path("key").asText(),
                    "doc_count", b.path("doc_count").asLong(),
                    "p50", values.hasNonNull("50.0") ? values.get("50.0").asDouble() : null,
                    "p95", values.hasNonNull("95.0") ? values.get("95.0").asDouble() : null));
        }

        return LevelResult.builder("一次请求算出 " + categoryBuckets.size()
                        + " 个类目 × 各 2 个头部品牌的均价与价格分位，外加 24 个月的均价走势——不需要多次往返数据库")
                .metrics(
                        new Metric("父桶数", categoryBuckets.size(), null),
                        new Metric("子桶总数", rows.size(), null),
                        new Metric("时间桶数", months.size(), null),
                        new Metric("请求耗时", res.path("took").asInt() + " ms", null))
                .table(new TableData(
                        List.of("类目", "类目文档数", "头部品牌", "品牌文档数", "均价", "最高价"), rows))
                .chart(new BarChartData(recent, "元（月均价）",
                        "近 12 个月在售商品均价（date_histogram + avg 子聚合）"))
                .json(Json.map("categoryBuckets", p95Rows))
                .notes(
                        "子聚合的 doc_count 之和 ≤ 父桶 doc_count：因为 top_brands 只取 size=2，其余品牌进了 sum_other_doc_count。",
                        "stats 聚合一次返回 count/min/max/avg/sum；percentiles 给出分布形态，比平均值更能反映\"长尾价格\"。",
                        "date_histogram 用 calendar_interval（month/1d 等）而不是固定 interval 秒数，能正确处理时区与闰月。",
                        "聚合的开销与\"桶数量\"成正比，不是与文档数成正比；对高基数字段做 terms(size=10000) 会拖垮集群。")
                .build();
    }
}
