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
 *
 * 本关给三条可核对的东西：
 *   ① 每个「类目 × 品牌」的 doc_count，用同条件的 filter 查询回查一次，必须相等；
 *   ② 时间直方图用 min_doc_count:0 + extended_bounds，末尾会出现 0 文档的桶（时间序列补零要用它）；
 *   ③ percentiles 的解释：p50 与 avg 不是一回事，长尾会把 avg 拉高。
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
        // @focus-start 类目 → 品牌 → 价格统计，再单独挂一个按月的时间直方图（含空桶）
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
                                        "min_doc_count", 0,
                                        "extended_bounds", Json.map("max", "2026-12-31")),
                                "aggs", Json.map(
                                        "avg_price", Json.map("avg", Json.map("field", "price")))))));
        // @focus-end

        JsonNode categoryBuckets = res.path("aggregations").path("by_category").path("buckets");

        // @focus-start 交叉核对：桶里的 doc_count 必须等于同条件 filter 查询的计数
        List<List<Object>> rows = new ArrayList<>();
        int mismatch = 0;
        for (JsonNode c : categoryBuckets) {
            String catKey = c.path("key").asText();
            for (JsonNode b : c.path("top_brands").path("buckets")) {
                String brandKey = b.path("key").asText();
                long bucketCount = b.path("doc_count").asLong();
                long filtered = countStatusBrand(catKey, brandKey);
                if (filtered != bucketCount) mismatch++;
                rows.add(TableData.row(
                        catKey,
                        c.path("doc_count").asLong(),
                        brandKey,
                        bucketCount,
                        Timing.round(b.path("price_stats").path("avg").asDouble(), 2),
                        Timing.round(b.path("price_stats").path("max").asDouble(), 2),
                        filtered == bucketCount ? "✓ " + filtered : "✗ " + filtered));
            }
        }
        // @focus-end

        JsonNode monthBuckets = res.path("aggregations").path("by_month").path("buckets");
        List<BarChartData.Item> months = new ArrayList<>();
        int emptyBuckets = 0;
        for (JsonNode b : monthBuckets) {
            String key = b.path("key_as_string").asText();
            JsonNode avg = b.path("avg_price").path("value");
            long docs = b.path("doc_count").asLong();
            if (docs == 0) emptyBuckets++;
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
                        + " 个类目 × 各 2 个头部品牌的均价与价格分位，外加 " + monthBuckets.size()
                        + " 个月的价格走势（其中 " + emptyBuckets + " 个月是 0 文档的空桶）"
                        + "——每个桶的 doc_count 都能被一条 filter 查询回查验证")
                .metrics(
                        new Metric("父桶数", categoryBuckets.size(), null),
                        new Metric("子桶总数", rows.size(), null),
                        new Metric("时间桶数", monthBuckets.size(), "含 " + emptyBuckets + " 个空桶"),
                        new Metric("空桶（0 文档）", emptyBuckets, "min_doc_count:0 + extended_bounds 才会出现"),
                        new Metric("桶计数核对", mismatch == 0 ? "全部一致" : mismatch + " 处不一致",
                                "与同条件 filter 查询逐个比对"),
                        new Metric("请求耗时", res.path("took").asInt() + " ms", null))
                .table(new TableData(
                        List.of("类目", "类目文档数", "头部品牌", "品牌文档数", "均价", "最高价", "filter 回查"), rows))
                .chart(new BarChartData(recent, "元（月均价）",
                        "近 12 个月在售商品均价（date_histogram + avg 子聚合；0 表示该月无数据）"))
                .json(Json.map("categoryBuckets", p95Rows,
                        "emptyBuckets", emptyBuckets,
                        "mismatch", mismatch))
                .notes(
                        "子聚合的 doc_count 之和 ≤ 父桶 doc_count：因为 top_brands 只取 size=2，其余品牌进了 sum_other_doc_count。",
                        "第 7 列是核对列：拿「类目 + 品牌 + status:1」再查一次 _count，结果必须等于桶里的 doc_count —— 这一条能把「聚合算错」和「查询写错」分开。",
                        "min_doc_count:0 配 extended_bounds 会补出空桶（本关末尾的月份），做时间序列补零时用它；不写 extended_bounds 时空桶不会出现，因为没有桶边界。",
                        "stats 聚合一次返回 count/min/max/avg/sum；percentiles 给出分布形态 —— 长尾价格会把 avg 拉高，p50 才是「典型值」。",
                        "date_histogram 用 calendar_interval（month/1d 等）而不是固定 interval 秒数，能正确处理时区与闰月。",
                        "聚合的开销与\"桶数量\"成正比，不是与文档数成正比；对高基数字段做 terms(size=10000) 会拖垮集群。")
                .build();
    }

    private long countStatusBrand(String category, String brand) {
        JsonNode r = es.post("/" + V2 + "/_count", Json.map(
                "query", Json.map("bool", Json.map(
                        "filter", Json.list(
                                Json.map("term", Json.map("category", category)),
                                Json.map("term", Json.map("brand", brand)),
                                Json.map("term", Json.map("status", 1)))))));
        return r.path("count").asLong(0);
    }
}
