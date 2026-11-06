package com.eslab.experiments;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.ExperimentModule;
import com.eslab.model.ExperimentResult;
import com.eslab.seed.Generator;
import com.eslab.seed.ProductDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 实验 D：批量导入时 refresh 策略的代价
 *
 * 同一批 5000 条文档，唯一区别是 refresh_interval：
 *   A：1s（默认）—— 导入过程中每 1s 生成新段，可能触发 merge
 *   B：-1（导入期关闭）+ 导完手动 refresh
 *
 * 两个临时索引都在 finally 里删掉，实验不把索引留在环境里。
 */
@Component
public class ExpRefreshBulk implements ExperimentModule {

    private static final int N = 20_000;
    private static final int BATCH = 2_000;
    /** 每批之间停一下，让 refresh_interval=1s 的那一组真的经历几次刷新；两组都停，保证公平 */
    private static final long PAUSE_MS = 350;
    private static final String ON = "eslab-demo-refresh-on";
    private static final String OFF = "eslab-demo-refresh-off";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EsClient es;

    public ExpRefreshBulk(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "exp-refresh-bulk";
    }

    @Override
    public String title() {
        return "批量导入：refresh_interval 的代价";
    }

    @Override
    public String question() {
        return "同一批 " + N + " 条文档，导入期开 1s 刷新 vs 关掉刷新，吞吐差多少？";
    }

    @Override
    public ExperimentResult run() throws Exception {
        List<Map<String, Object>> docs = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            ProductDoc d = Generator.makeDoc(i, 42);
            docs.add(Json.map(
                    "sku", d.sku(),
                    "title", d.title(),
                    "price", d.price(),
                    "category", d.category(),
                    "tags", d.tags()));
        }

        CaseResult a = runCase(ON, "1s", docs);
        CaseResult b = runCase(OFF, "-1", docs);

        double aPer = Timing.round(a.bulkMs / N, 4);
        double bPer = Timing.round(b.bulkMs / N, 4);
        double pct = a.bulkMs > 0 ? ((a.bulkMs - b.bulkMs) / a.bulkMs) * 100 : 0;

        String timePart = Math.abs(pct) < 10
                ? "写入耗时差异不显著（" + num(Timing.round(a.bulkMs)) + "ms vs "
                        + num(Timing.round(b.bulkMs)) + "ms，差 " + num(Timing.round(Math.abs(pct)))
                        + "% 落在噪声内）"
                : "写入耗时 " + num(Timing.round(a.bulkMs)) + "ms → " + num(Timing.round(b.bulkMs))
                        + "ms（" + (pct > 0 ? "快 " : "反而慢 ") + num(Timing.round(Math.abs(pct))) + "%）";
        String segPart = a.segments == b.segments
                ? "两组段数相同（" + a.segments + " 个），本次数据量下没有拉开差距"
                : "段数 " + a.segments + " → " + b.segments + "（关刷新后段更少，查询要遍历的索引结构也更少）";

        String conclusion = segPart + "；" + timePart + "。";

        return new ExperimentResult(
                id(),
                title(),
                question(),
                new ExperimentResult.Side(
                        "refresh_interval = 1s（默认）",
                        Timing.round(a.bulkMs),
                        "ms",
                        List.of(Timing.round(a.bulkMs)),
                        num(aPer) + " ms/文档，导入后 " + a.segments + " 个段"),
                new ExperimentResult.Side(
                        "refresh_interval = -1（导入期关闭）",
                        Timing.round(b.bulkMs),
                        "ms",
                        List.of(Timing.round(b.bulkMs)),
                        num(bPer) + " ms/文档，导入后 " + b.segments + " 个段"),
                new ExperimentResult.Delta(Timing.round(a.bulkMs / Math.max(b.bulkMs, 0.01), 2),
                        Timing.round(pct)),
                conclusion,
                List.of(
                        "这是单次测量，不是多轮中位数：每组 2 万条、分 10 批，批间停 " + PAUSE_MS
                                + "ms 让 1s 刷新真的发生几次；更大规模下差异会更明显。",
                        "关刷新期间数据不可搜索；如果导入与查询并存，需要评估「暂时不可见」是否可接受。",
                        "段数减少的收益是长期的（后续查询要遍历的段更少），但会被自动 merge 逐步抹平。",
                        "两组都在 finally 里删除了临时索引，实验不会把索引留在环境里。"));
    }

    /** bulkMs 只累计 bulk 请求本身，不含批间等待 */
    private record CaseResult(double bulkMs, long segments) {
    }

    /**
     * 跑一种 refresh 策略：建临时索引 → 分批 bulk → 手动 refresh → 读段数。
     * 无论中途是否抛错，finally 都会删掉临时索引。
     */
    private CaseResult runCase(String name, String refresh, List<Map<String, Object>> docs) throws Exception {
        deleteIndexIfExists(name);
        try {
            // @focus-start 建索引时唯一的变量就是 refresh_interval
            es.put("/" + name, definition(refresh));
            double ms = load(name, docs);
            // @focus-end
            es.post("/" + name + "/_refresh", null);
            JsonNode stat = es.get("/" + name + "/_stats/segments");
            long segments = stat.path("indices").path(name).path("primaries").path("segments").path("count").asLong();
            return new CaseResult(ms, segments);
        } finally {
            deleteIndexIfExists(name);
        }
    }

    /** 分批灌数据，返回客户端视角的 bulk 累计耗时（不含批间等待） */
    private double load(String index, List<Map<String, Object>> docs) throws Exception {
        double total = 0;
        for (int i = 0; i < docs.size(); i += BATCH) {
            List<Map<String, Object>> slice = docs.subList(i, Math.min(i + BATCH, docs.size()));
            StringBuilder ndjson = new StringBuilder(slice.size() * 600);
            for (Map<String, Object> d : slice) {
                ndjson.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
                ndjson.append(MAPPER.writeValueAsString(d)).append('\n');
            }
            // @focus-start 一次 bulk 打 2000 条：批越大，单次 refresh 摊到每条上的成本越低
            long t0 = System.nanoTime();
            es.bulk(ndjson.toString(), 120_000);
            total += (System.nanoTime() - t0) / 1_000_000.0;
            // @focus-end
            // 批间停顿：让 refresh_interval=1s 的索引真的经历几次刷新（不计入耗时）
            Thread.sleep(PAUSE_MS);
        }
        return total;
    }

    private static Map<String, Object> definition(String refresh) {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "refresh_interval", refresh),
                "mappings", Json.map(
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "category", Json.map("type", "keyword"),
                                "tags", Json.map("type", "keyword"))));
    }

    private void deleteIndexIfExists(String index) {
        if (!index.startsWith("eslab-")) {
            throw new IllegalArgumentException("拒绝操作非 eslab- 前缀索引：" + index);
        }
        try {
            es.call("HEAD", "/" + index, null, null, 5_000);
        } catch (EsException e) {
            return; // 不存在即视为已清理
        }
        try {
            es.delete("/" + index);
        } catch (EsException ignored) {
            // 删除失败留给下次运行的开头再清一次
        }
    }

    /** TS 的模板字符串不会输出无意义的小数位，这里保持同样观感 */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
