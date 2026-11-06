package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.EsResponse;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m6-2 search_after + PIT：深分页的正确姿势
 *
 * 这一关要证明的不是「search_after 更快」，而是**两种代价的来源不同**：
 *   · from+size 的代价在「定位」—— 每个分片都要吐出 from+size 条候选参与堆排序，越深越贵，
 *     而且有 max_result_window=10000 的硬上限；
 *   · search_after 的代价在「翻页」—— 每页每片只取 size 条，和翻到第几页无关。
 *
 * 因此本关在同一个 PIT 里，从第 1 / 51 / 100 页三个起点各连翻 4 页，逐页记录
 * 每片候选量、ES 的 took、客户端往返耗时与末条游标；另抓一次 from=15000 的真实 400。
 *
 * 口径说明（这是这一关最容易做错的地方）：
 *   对比只能用 took。客户端墙钟包含约 4ms 的固定开销（建连、序列化、JSON 解析），
 *   而 from 从 0 到 9900 的差异只有 1–3ms —— 用墙钟看会得出「深翻不花钱」的错误结论。
 *   本关第一次实测就是这么被盖住的，所以表格把两列并排给出，结论只取 took。
 */
@Component
public class M62SearchAfter implements LevelModule {

    private static final String V2 = "eslab-products-v2";
    private static final int SIZE = 100;
    private static final int PAGES = 4;
    /** 三个起点：第 1 页、第 51 页、第 100 页（from=9900 + size=100 正好到 10000 的上限） */
    private static final int[] STARTS = {0, 5_000, 9_900};
    /** 超过 max_result_window 的 from：用来抓真实的 400，而不是靠文案断言 */
    private static final int ILLEGAL_FROM = 15_000;
    private static final int ROUNDS = 5;

    private final EsClient es;

    public M62SearchAfter(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m6-2";
    }

    /** 一页的观测值：页码、方式、条数、每片候选量、took 样本、客户端耗时样本、末条游标 */
    private record Page(int pageNo, String mode, int docs, int candidatesPerShard,
                        Timing.Sample took, Timing.Sample wall, String cursor) {
    }

    @Override
    public LevelResult run() throws Exception {
        int openBaseline = openContexts();
        JsonNode pit = es.call("POST", "/" + V2 + "/_pit?keep_alive=1m", null).body();
        String pitId = pit.path("id").asText();
        int openWithPit = openContexts();

        // 预热：一次 size=0 的 PIT 查询，把连接、JIT、页缓存热起来；不参与任何样本
        es.call("POST", "/_search", body(pitId, 0, null, 0));

        List<Page> pages = new ArrayList<>();
        List<Map<String, Object>> pageJson = new ArrayList<>();
        long total = 0;

        for (int start : STARTS) {
            // ① 定位：from+size 一次跳到深处（真实业务里这一步换成「上一批的游标」，不需要付出这笔钱）
            JsonNode[] posBody = new JsonNode[1];
            List<Double> posTook = new ArrayList<>();
            Timing.Sample posWall = Timing.sample(ROUNDS, 0, round -> {
                EsResponse res = es.call("POST", "/_search", body(pitId, start, null, SIZE));
                posBody[0] = res.body();
                posTook.add((double) res.body().path("took").asInt());
                return res.ms();
            });
            Timing.Sample posTookSample = Timing.summarize(posTook);
            JsonNode cursor = lastSort(posBody[0]);
            total = posBody[0].path("hits").path("total").path("value").asLong();
            int pageNo = start / SIZE + 1;

            pages.add(new Page(pageNo, "from=" + fmt(start) + " 定位",
                    posBody[0].path("hits").path("hits").size(), start + SIZE,
                    posTookSample, posWall, cursorText(cursor)));
            pageJson.add(pageJson(pageNo, "from=" + fmt(start) + " 定位", posBody[0],
                    posTookSample, posWall, cursorText(cursor), start + SIZE));

            // ② 游标翻页：每页只带上一页最后一条的 sort 值，不带 from
            for (int p = 1; p <= PAGES; p++) {
                JsonNode fixed = cursor;
                JsonNode[] pageBody = new JsonNode[1];
                List<Double> pageTook = new ArrayList<>();
                Timing.Sample wall = Timing.sample(ROUNDS, 0, round -> {
                    EsResponse res = es.call("POST", "/_search", body(pitId, -1, fixed, SIZE));
                    pageBody[0] = res.body();
                    pageTook.add((double) res.body().path("took").asInt());
                    return res.ms();
                });
                Timing.Sample took = Timing.summarize(pageTook);
                pageNo++;
                cursor = lastSort(pageBody[0]);
                pages.add(new Page(pageNo, "search_after", pageBody[0].path("hits").path("hits").size(),
                        SIZE, took, wall, cursorText(cursor)));
                pageJson.add(pageJson(pageNo, "search_after", pageBody[0], took, wall,
                        cursorText(cursor), SIZE));
            }
        }

        // ③ 越界：from=15000 已经超过 max_result_window(10000)，ES 返回 400 而不是变慢
        int illegalStatus = 200;
        JsonNode illegalBody = null;
        long illegalStart = System.nanoTime();
        try {
            es.call("POST", "/" + V2 + "/_search", Json.map(
                    "from", ILLEGAL_FROM,
                    "size", SIZE,
                    "sort", Json.list(Json.map("price", "asc"), Json.map("sku", "asc")),
                    "_source", Json.list("sku")));
        } catch (EsException e) {
            illegalStatus = e.status();
            illegalBody = e.payload();
        }
        double illegalMs = (System.nanoTime() - illegalStart) / 1_000_000.0;

        // ④ 关闭 PIT，并用同一个 id 再查一次：释放不是「等 keep_alive 到期」，是当场可验证的
        JsonNode closed = es.call("DELETE", "/_pit", Json.map("id", pitId)).body();
        int openAfter = openContexts();
        int reuseStatus = 0;
        String reuseType = "";
        try {
            es.call("POST", "/_search", body(pitId, 0, null, SIZE));
        } catch (EsException e) {
            reuseStatus = e.status();
            reuseType = e.payload().path("error").path("type").asText("");
        }

        List<Double> tookRuns = new ArrayList<>();
        for (Page p : pages) {
            if ("search_after".equals(p.mode())) tookRuns.add(p.took().median());
        }
        double cursorMin = tookRuns.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double cursorMax = tookRuns.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double cursorMedian = median(tookRuns);
        Page p1 = pages.get(0);
        Page p51 = pages.get(PAGES + 1);
        Page p100 = pages.get(2 * (PAGES + 1));

        List<List<Object>> rows = new ArrayList<>();
        for (Page p : pages) {
            rows.add(TableData.row(p.pageNo(), p.mode(), p.docs(), fmt(p.candidatesPerShard()),
                    num(p.took().median()), p.cursor()));
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("pitId", pitId);
        json.put("pages", pageJson);
        json.put("illegalFrom", Json.map(
                "from", ILLEGAL_FROM, "size", SIZE,
                "status", illegalStatus, "clientMs", round(illegalMs), "body", illegalBody));
        json.put("close", closed);
        json.put("reuseAfterClose", Json.map("status", reuseStatus, "errorType", reuseType));
        json.put("openContexts", Json.map(
                "baseline", openBaseline, "withPit", openWithPit, "afterClose", openAfter));

        String cursorSpan = cursorMax - cursorMin < 0.05
                ? "恒为 " + num(cursorMedian)
                : num(cursorMin) + "–" + num(cursorMax);
        String summary = "同一 PIT 里从第 1 / 51 / 100 页各连翻 " + PAGES + " 页：定位的 took "
                + num(p1.took().median()) + " / " + num(p51.took().median()) + " / "
                + num(p100.took().median()) + " ms（每片候选 " + fmt(p1.candidatesPerShard()) + " / "
                + fmt(p51.candidatesPerShard()) + " / " + fmt(p100.candidatesPerShard())
                + " 条）；游标翻页 " + cursorSpan
                + " ms；from=" + fmt(ILLEGAL_FROM) + " 直接 " + illegalStatus + "（窗口上限 10000）";

        List<BarChartData.Item> chart = new ArrayList<>();
        chart.add(new BarChartData.Item("定位第 1 页", p1.took().median()));
        chart.add(new BarChartData.Item("定位第 51 页", p51.took().median()));
        chart.add(new BarChartData.Item("定位第 100 页", p100.took().median()));
        chart.add(new BarChartData.Item("第 100 页后单页", cursorMedian));

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("第 1 页定位", num(p1.took().median()) + " ms",
                                "took · 每片候选 " + fmt(p1.candidatesPerShard()) + " 条"),
                        new Metric("第 100 页定位", num(p100.took().median()) + " ms",
                                "took · 每片候选 " + fmt(p100.candidatesPerShard()) + " 条"),
                        new Metric("游标单页", num(cursorMedian) + " ms",
                                "took 中位 · 每片候选恒为 " + SIZE + " 条"),
                        new Metric("命中总量", fmt(total), "track_total_hits=true，精确值"),
                        new Metric("from=" + fmt(ILLEGAL_FROM), illegalStatus + " 拒绝",
                                "超过 max_result_window(10000)"),
                        new Metric("PIT 上下文", openBaseline + " → " + openWithPit + " → " + openAfter,
                                "基线 → 开 PIT（每分片 +1）→ 关闭；num_freed="
                                        + closed.path("num_freed").asInt()
                                        + "，旧 id 再用 " + reuseStatus + " " + reuseType))
                .table(TableData.of(
                        List.of("页序", "方式", "条数", "每片候选", "took (ms)", "末条游标"), rows))
                .chart(new BarChartData(chart, "ms",
                        "用 ES 的 took 比较：客户端墙钟有约 4ms 固定开销，会把这几毫秒的差异盖掉"))
                .json(json)
                .notes(
                        "对比只用 took，不用客户端墙钟：墙钟 = 网络 + 序列化 + ES 处理，固定的那几毫秒"
                                + "比 from 从 0 到 9900 带来的差异还大。用墙钟会得出「定位不涨」的错误结论 —— 本项目第一次实现就踩了这个坑。",
                        "took 的分辨率只有 1ms，5 轮中位数仍会在 1–4ms 之间抖动，所以表格里"
                                + "「51 页比 100 页贵」这种反序是量化噪声，不要当成曲线。这一关能确定的是两件事："
                                + "每片候选量随 from 线性上涨（第 4 列），以及 from 超过窗口就 400。",
                        "两个方案的代价来源不同：from+size 让每个分片多吐 from 条候选参与堆排序（候选量见表格第 4 列），"
                                + "search_after 每片恒定只取 size 条。分片越多，前者的放大越明显。",
                        "排序用 price + _shard_doc：_shard_doc 是 PIT 内的稳定递增字段，只在同一个 PIT 里有效 —— "
                                + "跨 PIT 拿它当游标会错位，所以「游标」和「快照」必须成对使用。",
                        "三个起点的「定位」用 from 是为了让查询凭空出现在第 100 页；真实业务里游标是上一批请求的产物，"
                                + "不需要付出这笔定位成本 —— 这也是 search_after 在顺序遍历里便宜的原因。",
                        "from+size 的上限是 max_result_window=10000：from=" + fmt(ILLEGAL_FROM)
                                + " + size=" + SIZE + " 直接被 400 拒绝 —— 不是慢，是不可用，调大窗口只是把问题推后。",
                        "PIT 的释放有硬证据：DELETE 返回 num_freed=" + closed.path("num_freed").asInt()
                                + "，open_contexts 从 " + openWithPit + " 回到 " + openAfter
                                + "（基线 " + openBaseline + "；基线不为 0 说明还有别的 PIT 没关，"
                                + "那一份不属于本关），再用旧 id 查询得到 " + reuseStatus + " " + reuseType
                                + "。不关的话它会一直持有段引用，阻碍 merge。",
                        "本机 2 万条、3 分片，绝对差只有几毫秒 —— 这组数字证明的是「候选量随 from 增长」这个机制，"
                                + "不能外推成「线上会慢几倍」；数据量、分片数、查询成本都会改变斜率。")
                .build();
    }

    /** PIT 内的查询体：from 只在「定位」时给，翻页只给 search_after */
    private Map<String, Object> body(String pitId, int from, JsonNode cursor, int size) {
        Map<String, Object> b = Json.map(
                "size", size,
                "pit", Json.map("id", pitId, "keep_alive", "1m"),
                "sort", Json.list(Json.map("price", "asc"), Json.map("_shard_doc", "asc")),
                "_source", Json.list("sku"),
                "track_total_hits", true);
        if (from >= 0) b.put("from", from);
        if (cursor != null) b.put("search_after", cursor);
        return b;
    }

    private static JsonNode lastSort(JsonNode response) {
        JsonNode hits = response.path("hits").path("hits");
        if (hits.isEmpty()) return null;
        return hits.get(hits.size() - 1).path("sort");
    }

    /** 游标展示成「价格/分片内序号」，原始 sort 数组放在 json 里 */
    private static String cursorText(JsonNode sort) {
        if (sort == null || sort.isEmpty()) return "—";
        double price = sort.get(0).asDouble(Double.NaN);
        String priceText = Double.isNaN(price) ? sort.get(0).asText("?")
                : String.format(Locale.ROOT, "%.2f", price);
        return priceText + "/" + sort.get(1).asText("?");
    }

    private static Map<String, Object> pageJson(int pageNo, String mode, JsonNode body,
                                                Timing.Sample took, Timing.Sample wall,
                                                String cursor, int candidates) {
        return Json.map(
                "page", pageNo,
                "mode", mode,
                "docs", body.path("hits").path("hits").size(),
                "candidatesPerShard", candidates,
                "shards", body.path("_shards").path("total").asInt(),
                "took", body.path("took").asInt(),
                "tookRuns", took.runs(),
                "total", body.path("hits").path("total").path("value").asLong(),
                "clientMsRuns", wall.runs(),
                "cursor", cursor);
    }

    private int openContexts() {
        JsonNode nodes = es.get("/_nodes/stats/indices/search").path("nodes");
        var it = nodes.fieldNames();
        if (!it.hasNext()) return -1;
        return nodes.path(it.next()).path("indices").path("search").path("open_contexts").asInt(-1);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        int n = sorted.size();
        double m = n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
        return Math.round(m * 100) / 100.0;
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private static String num(double v) {
        String s = String.format(Locale.ROOT, "%.2f", v);
        return s.endsWith(".00") ? s.substring(0, s.length() - 3) : s;
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
