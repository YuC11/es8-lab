package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m6-3 用 profile 定位「时间花在哪」，用段统计看「为什么缓存没用上」。
 *
 * 这一关最容易讲错的地方是「把节点时间加起来」：父节点的 time_in_nanos 已经包含子节点，
 * 父子相加会把同一段时间算两次、三次。所以本关对每个节点同时给两列：
 *   · 累计耗时 = 该节点的 time_in_nanos（含子节点）
 *   · 自身耗时 = 累计 − 直接子节点累计（这才是「这个节点自己花的钱」）
 * 只有自身耗时在兄弟节点之间可以横向比较，也只有它加起来不会重复计数。
 *
 * 另一条边界：query / collector / fetch 是同一请求的三个阶段，分片之间并行，
 * 所以它们的和是「分片内 CPU 时间之和」，不是墙钟；墙钟只有一个，就是响应顶层的 took。
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

    /** 树上的一个节点：阶段、层级、显示名、累计、自身、说明 */
    private record NodeRow(String phase, int depth, String label, double totalMs, double selfMs, String desc) {
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

        JsonNode shards = profiled.path("profile").path("shards");

        // 三段合计（所有分片），以及「最贵的单个节点」——只看自身耗时，不看累计
        double queryTotal = 0;
        double collectorTotal = 0;
        double fetchTotal = 0;
        long rewriteTotal = 0;

        List<NodeRow> rows = new ArrayList<>();
        boolean treeRendered = false;

        for (JsonNode shard : shards) {
            for (JsonNode search : shard.path("searches")) {
                JsonNode query = search.path("query");
                for (JsonNode root : query) queryTotal += nanosToMs(root.path("time_in_nanos").asLong());

                double shardCollector = 0;
                for (JsonNode c : search.path("collector")) {
                    shardCollector += nanosToMs(c.path("time_in_nanos").asLong());
                }
                collectorTotal += shardCollector;

                // fetch 在**分片级**，不在 searches[] 里 —— 这是 profile 结构最容易看漏的一处
                JsonNode fetch = shard.path("fetch");
                double shardFetch = nanosToMs(fetch.path("time_in_nanos").asLong());
                fetchTotal += shardFetch;
                rewriteTotal += search.path("rewrite_time").asLong();

                // 树只渲染第一个分片：3 个分片结构同构，重复三遍只会把表格撑长
                if (!treeRendered && !query.isEmpty()) {
                    treeRendered = true;
                    for (JsonNode root : query) walk(root, 0, "query", rows);
                    for (JsonNode c : search.path("collector")) walkCollector(c, 0, rows);
                    if (!fetch.isMissingNode()) {
                        JsonNode bd = fetch.path("breakdown");
                        rows.add(new NodeRow("fetch", 0, "fetch 阶段",
                                nanosToMs(fetch.path("time_in_nanos").asLong()),
                                nanosToMs(fetch.path("time_in_nanos").asLong()),
                                "load_source " + fmt(nanosToMs(bd.path("load_source").asLong()))
                                        + "ms / load_stored_fields "
                                        + fmt(nanosToMs(bd.path("load_stored_fields").asLong())) + "ms"));
                    }
                }
            }
        }

        // 最贵节点从渲染出来的树里挑：只看「自身耗时」，累计耗时必然是根节点最大，没有信息量
        String hottestLabel = "—";
        double hottestSelf = 0;
        for (NodeRow r : rows) {
            if (r.selfMs() > hottestSelf) {
                hottestSelf = r.selfMs();
                hottestLabel = r.label();
            }
        }

        JsonNode segStats = es.get("/" + V2 + "/_stats/segments,store,docs");
        JsonNode prim = segStats.path("indices").path(V2).path("primaries");
        JsonNode nodeStats = es.get("/_nodes/stats/indices/search");
        var nodeIds = nodeStats.path("nodes").fieldNames();
        String nodeId = nodeIds.hasNext() ? nodeIds.next() : "";
        JsonNode searchStats = nodeStats.path("nodes").path(nodeId).path("indices").path("search");

        long segments = prim.path("segments").path("count").asLong();
        long docs = prim.path("docs").path("count").asLong();
        int took = profiled.path("took").asInt();

        List<List<Object>> table = new ArrayList<>();
        for (NodeRow r : rows) {
            table.add(TableData.row(r.phase(), indent(r.depth()) + r.label(),
                    fmt(r.totalMs()), fmt(r.selfMs()), r.desc()));
        }

        List<BarChartData.Item> chart = new ArrayList<>();
        chart.add(new BarChartData.Item("query", round(queryTotal)));
        chart.add(new BarChartData.Item("collector", round(collectorTotal)));
        chart.add(new BarChartData.Item("fetch", round(fetchTotal)));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("took", took);
        json.put("profile", profiled.path("profile"));
        json.put("totalsMs", Json.map(
                "query", round(queryTotal),
                "collector", round(collectorTotal),
                "fetch", round(fetchTotal),
                "rewriteNanos", rewriteTotal));
        json.put("segments", prim.path("segments"));
        json.put("nodeSearch", Json.map(
                "open_contexts", searchStats.path("open_contexts").asLong(),
                "query_total", searchStats.path("query_total").asLong(),
                "query_time_in_millis", searchStats.path("query_time_in_millis").asLong(),
                "fetch_total", searchStats.path("fetch_total").asLong()));

        String summary = "took=" + took + "ms（墙钟）；profile 里 query 根节点 " + shards.size()
                + " 个分片合计 " + fmt(queryTotal) + "ms、collector " + fmt(collectorTotal)
                + "ms、fetch " + fmt(fetchTotal) + "ms；最贵的单个节点是「" + hottestLabel
                + "」，自身 " + fmt(hottestSelf) + "ms —— 拿它定位热点，别把节点时间相加当墙钟";

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("took（墙钟）", took + " ms", "响应顶层，含 query + fetch"),
                        new Metric("query 根节点合计", fmt(queryTotal) + " ms",
                                shards.size() + " 个分片相加、含子节点；分片并行，合计可大于 took"),
                        new Metric("collector 合计", fmt(collectorTotal) + " ms", "打分与取 top-k"),
                        new Metric("fetch 合计", fmt(fetchTotal) + " ms", "取 _source，与 size 相关"),
                        new Metric("最贵节点（自身）", fmt(hottestSelf) + " ms",
                                hottestLabel + " · 只算自己的时间"),
                        new Metric("段数", segments, "单段平均 " + Math.round((double) docs / Math.max(1, segments)) + " 条"))
                .table(TableData.of(
                        List.of("阶段", "节点（缩进即父子）", "累计 (ms)", "自身 (ms)", "说明"), table))
                .chart(new BarChartData(chart, "ms",
                        "三段都是分片内累计时间，用来横向找热点；它们的和不是墙钟"))
                .json(json)
                .notes(
                        "自身耗时 = 累计 − 直接子节点累计。父节点的累计已经包含子节点，父子相加会把同一段时间算两三次 —— "
                                + "本关之前就是这么算的，所以现在把两列并排给出，让「哪一列能加」变得可检查。",
                        "表格里是第 1 个分片的原始树（3 个分片结构同构），所以单看某一行会小于指标里的「合计」；"
                                + "合计是 3 个分片相加，用来判断哪一段占比最大。rewrite_time 单独在响应里，本关放在 json。",
                        "query / collector / fetch 是同一请求的三个阶段，分片之间并行执行；三者相加是「分片 CPU 时间之和」，"
                                + "不是一次请求的耗时。墙钟只有 took 一个数。",
                        "profile 本身有开销，只用于定位；单次数字抖动很大，看占比和数量级就够，"
                                + "要比较优化前后必须多轮采样取中位 —— 本关不把它当基准。",
                        "fetch 在**分片级**（shard.fetch），不在 searches[] 里 —— 这是读 profile 结构最容易漏的一段；"
                                + "有聚合时同一层还会多一段 aggregations。fetch 的耗时与返回条数、_source 大小相关，"
                                + "本关 size=10 所以它很小，返回全量 _source 或做大高亮时才会变明显。",
                        "段统计回答另一个问题：段越多，每次查询要打开的索引结构越多、filter cache 越碎。"
                                + "profile 不会告诉你段数，这两类证据要分开取。")
                .build();
    }

    /** query 树：逐层给累计与自身耗时 */
    private void walk(JsonNode node, int depth, String phase, List<NodeRow> rows) {
        if (!node.isObject()) return;
        long time = node.path("time_in_nanos").asLong();
        long childSum = 0;
        for (JsonNode child : node.path("children")) childSum += child.path("time_in_nanos").asLong();
        String label = node.path("type").asText("unknown");
        rows.add(new NodeRow(phase, depth, label,
                nanosToMs(time), nanosToMs(Math.max(0, time - childSum)),
                shorten(node.path("description").asText(""))));
        for (JsonNode child : node.path("children")) walk(child, depth + 1, phase, rows);
    }

    /** collector 树：节点用 name/reason，不用 type */
    private void walkCollector(JsonNode node, int depth, List<NodeRow> rows) {
        if (!node.isObject()) return;
        long time = node.path("time_in_nanos").asLong();
        long childSum = 0;
        for (JsonNode child : node.path("children")) childSum += child.path("time_in_nanos").asLong();
        String label = node.path("name").asText("collector");
        String reason = node.path("reason").asText("");
        rows.add(new NodeRow("collector", depth, label,
                nanosToMs(time), nanosToMs(Math.max(0, time - childSum)),
                reason.isEmpty() ? "收集器" : "reason=" + reason));
        for (JsonNode child : node.path("children")) walkCollector(child, depth + 1, rows);
    }

    private static double nanosToMs(long nanos) {
        return round(nanos / 1_000_000.0);
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String indent(int depth) {
        return depth <= 0 ? "" : "  ".repeat(depth) + "└ ";
    }

    private static String shorten(String text) {
        if (text == null) return "";
        String t = text.replace('\n', ' ').trim();
        return t.length() <= 60 ? t : t.substring(0, 57) + "…";
    }
}
