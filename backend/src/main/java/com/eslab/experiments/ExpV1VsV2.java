package com.eslab.experiments;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.ExperimentModule;
import com.eslab.model.ExperimentResult;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 实验 C：动态映射 vs 显式映射（同一份数据、同一条查询）
 *
 * 这个实验的价值不在「快几毫秒」，而在于暴露一个更深的问题：
 * analyzer 选错会让查询语义完全改变（召回从万级变成千级），
 * 而只盯耗时会误判成「性能问题」。
 */
@Component
public class ExpV1VsV2 implements ExperimentModule {

    private static final String V1 = "eslab-products-v1";
    private static final String V2 = "eslab-products-v2";

    private static final Map<String, Object> QUERY = Json.map("match", Json.map("title", "蓝牙耳机"));

    private final EsClient es;

    public ExpV1VsV2(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "exp-v1-vs-v2";
    }

    @Override
    public String title() {
        return "动态映射 vs 显式映射";
    }

    @Override
    public String question() {
        return "同一份 20 万文档、同一条 match 查询，v1（全动态）与 v2（显式 Mapping + IK）差在哪？";
    }

    @Override
    public ExperimentResult run() throws Exception {
        long h1 = hitCount(V1);
        long h2 = hitCount(V2);

        Timing.Sample a = Timing.sample(5, 1, round -> run(V1));
        Timing.Sample b = Timing.sample(5, 1, round -> run(V2));
        Timing.Compare cmp = Timing.compare(a, b);

        String conclusion = "耗时 " + num(a.median()) + "ms → " + num(b.median()) + "ms（"
                + (cmp.improvementPct() > 0 ? "快" : "慢") + " " + num(Math.abs(cmp.improvementPct())) + "%），"
                + "但真正的差距是命中数 " + h1 + " → " + h2
                + "：v1 把中文按单字切开，召回被放大 "
                + String.format("%.1f", h1 / (double) Math.max(h2, 1))
                + " 倍，其中绝大多数是不相关结果";

        return new ExperimentResult(
                id(),
                title(),
                question(),
                new ExperimentResult.Side(
                        "v1 动态映射（standard 分词）",
                        a.median(),
                        "ms",
                        a.runs(),
                        "命中 " + h1 + " 条"),
                new ExperimentResult.Side(
                        "v2 显式映射（ik_max_word / ik_smart）",
                        b.median(),
                        "ms",
                        b.runs(),
                        "命中 " + h2 + " 条"),
                new ExperimentResult.Delta(cmp.speedup(), cmp.improvementPct()),
                conclusion,
                List.of(
                        "命中数差异属于语义正确性问题，不是性能优化能解决的；mapping 选错时\"更快\"往往意味着\"扫得更少、错得更多\"。",
                        "v1 体积 88MB vs v2 62.7MB，说明字段类型选择同时影响存储成本。",
                        "耗时差异受段结构、缓存状态影响大，不要用单次 took 比较两个索引。"));
    }

    /** 只取总命中数：用来对比两种 mapping 的召回差异 */
    private long hitCount(String index) {
        // @focus-start 同一条 match 查询打两个索引，唯一变量是 mapping
        JsonNode r = es.post("/" + index + "/_search", Json.map(
                "size", 0,
                "track_total_hits", true,
                "query", QUERY));
        // @focus-end
        return r.path("hits").path("total").path("value").asLong();
    }

    /** 计时用：真正返回 20 条文档，让 _source 取回也计入代价 */
    private double run(String index) {
        // @focus-start
        JsonNode r = es.post("/" + index + "/_search", Json.map(
                "size", 20,
                "track_total_hits", true,
                "_source", Json.list("title", "price"),
                "query", QUERY));
        // @focus-end
        return r.path("took").asDouble();
    }

    /** TS 的模板字符串不会输出无意义的小数位，这里保持同样观感 */
    private static String num(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
