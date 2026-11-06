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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M6-3 用 profile 看「时间花在哪」，用段统计看「为什么缓存没用上」
 *
 * profile 是 ES 自带的执行计划剖析器：给出 query / rewrite / collector 各阶段耗时。
 * 段（segment）是 Lucene 的最小检索单元：每个段一份倒排索引，查询要遍历所有段。
 */
@Component
public class M63Profile implements LevelModule {

    private static final String V2 = "eslab-products-v2";

    private final EsClient es;

    public M63Profile(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m6-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start profile 必须放在请求体里（8.x 也支持 URL 参数，但请求体更通用）
        JsonNode profiled = es.post("/" + V2 + "/_search", Json.map(
                "size", 10,
                "profile", true,
                "query", Json.map("bool", Json.map(
                        "must", Json.list(Json.map("match", Json.map("title", "蓝牙耳机"))),
                        "filter", Json.list(
                                Json.map("term", Json.map("category", "数码影音")),
                                Json.map("range", Json.map("price", Json.map("gte", 100)))))),
                "sort", Json.list(Json.map("price", "asc"))));
        // @focus-end

        // 只读取 profile 树用于定位，不将父子节点耗时相加：父节点的耗时包含子节点，
        // 求和会重复计数，也不能把 profile 时间当作请求墙钟耗时。
        Map<String, Long> totals = new LinkedHashMap<>();
        JsonNode shards = profiled.path("profile").path("shards");
        for (JsonNode shard : shards) {
            for (JsonNode search : shard.path("searches")) {
                for (JsonNode q : search.path("query")) collectProfileRoots(q, totals);
                if (search.hasNonNull("rewrite_time") && search.path("rewrite_time").asLong() != 0) {
                    totals.merge("rewrite", search.path("rewrite_time").asLong(), Long::sum);
                }
            }
        }

        List<Map.Entry<String, Long>> entries = new ArrayList<>(totals.entrySet());
        entries.sort((x, y) -> Long.compare(y.getValue(), x.getValue()));
        List<Phase> breakdown = new ArrayList<>();
        for (Map.Entry<String, Long> e : entries.subList(0, Math.min(6, entries.size()))) {
            breakdown.add(new Phase(e.getKey(), e.getValue() / 1e6));
        }

        // @focus-start 段统计：段越多，每次查询要「打开」的索引结构越多
        JsonNode segStats = es.get("/" + V2 + "/_stats/segments,store,docs");
        JsonNode prim = segStats.path("indices").path(V2).path("primaries");
        // @focus-end

        JsonNode nodeStats = es.get("/_nodes/stats/indices/search");
        Iterator<String> nodeIds = nodeStats.path("nodes").fieldNames();
        String nodeId = nodeIds.hasNext() ? nodeIds.next() : "";
        JsonNode searchStats = nodeStats.path("nodes").path(nodeId).path("indices").path("search");

        long segments = prim.path("segments").path("count").asLong();
        long docs = prim.path("docs").path("count").asLong();

        String topMs = breakdown.isEmpty() ? "—" : String.format(Locale.ROOT, "%.2f", breakdown.get(0).ms());
        String topType = breakdown.isEmpty() ? "—" : breakdown.get(0).type();

        List<BarChartData.Item> chartItems = new ArrayList<>();
        List<List<Object>> tableRows = new ArrayList<>();
        for (Phase b : breakdown) {
            chartItems.add(new BarChartData.Item(b.type(), Timing.round(b.ms(), 2)));
            tableRows.add(TableData.row(b.type(), Timing.round(b.ms(), 3)));
        }

        return LevelResult.builder("本次查询在 query 阶段累计 " + topMs + "ms（" + topType
                        + " 占大头），索引当前有 " + segments + " 个段——段数决定了每次查询要打开多少份倒排索引")
                .metrics(
                        new Metric("总耗时 took", profiled.path("took").asInt() + " ms", null),
                        new Metric("段数", segments, null),
                        new Metric("单段平均文档数", Math.round((double) docs / Math.max(1, segments)), null),
                        new Metric("节点累计查询次数", searchStats.path("query_total").asLong(), null))
                .chart(new BarChartData(chartItems, "ms", "profile 累计耗时（所有分片求和），不代表单次墙钟"))
                .table(new TableData(List.of("阶段 / 类型", "累计耗时 (ms)"), tableRows))
                .json(Json.map(
                        "segments", prim.path("segments"),
                        "storeBytes", prim.path("store").path("size_in_bytes").asLong(),
                        "nodeSearch", Json.map(
                                "query_total", searchStats.path("query_total").asLong(),
                                "query_time_in_millis", searchStats.path("query_time_in_millis").asLong()),
                        "profileShards", shards.size()))
                .notes(
                        "profile 的耗时是\"累计 CPU 时间\"，会把所有分片、所有子查询相加，所以总和通常远大于 took；看的是**占比**，不是绝对值。",
                        "段数越多，查询要遍历的段越多、filter cache 越难生效（缓存按段粒度缓存）。刚导入完数据时段很多，后续会自动 merge 到较少的大段。",
                        "不要一见段多就 force-merge：force-merge 会重写全部数据、占用大量 IO，期间查询和写入都会变慢。应先做 A/B 实测确认它真的能改善你的查询。",
                        "真正值得先看的是 query_time_in_millis / query_total 的趋势，以及是否有 fetch 阶段异常大（常见于返回大量 _source）。")
                .build();
    }

    private void collectProfileRoots(JsonNode node, Map<String, Long> totals) {
        if (!node.isObject()) return;
        String type = node.path("type").asText("unknown");
        long time = node.path("time_in_nanos").asLong();
        totals.merge(type, time, Long::sum);
        // 不递归累加 children；children 留在原始 profile 响应供读者逐层检查。
    }

    private record Phase(String type, double ms) {
    }
}
