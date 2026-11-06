package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M6-1 from+size 的真实代价：翻到第几页开始崩
 *
 * from+size 的语义是「每个分片各取 from+size 条，协调节点再归并取 [from, from+size)」。
 * 所以 from=9000 时，每个分片都要吐出 9020 条候选——代价随 from 线性上涨。
 */
@Component
public class M61DeepPaging implements LevelModule {

    private static final String V2 = "eslab-products-v2";
    private static final int[] FROM_STEPS = {0, 1000, 5000, 9000};

    private final EsClient es;

    public M61DeepPaging(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m6-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // 全局预热：首档如果冷启动，took 会明显偏高（实测出现 12ms vs 后面 3ms 的反序），
        // 与 from 的大小无关。先各打一次不计入样本，再看曲线。
        page(0);
        page(9000);

        List<PageSample> results = new ArrayList<>();
        // @focus-start 四个页码档位，各采 3 轮取中位数（只看 took，不含网络）
        for (int from : FROM_STEPS) {
            Timing.Sample t = Timing.sample(3, 0, round -> page(from));
            results.add(new PageSample(from, t.median(), t.runs()));
        }
        // @focus-end

        // 上限是「小于等于」：9980+20=10000 通过，9990+20=10010 被拒
        // 这一对对照比单看报错有用 —— 它证明边界是闭区间，不是「接近就危险」
        boolean atLimitOk;
        String limitError = "";
        try {
            es.post("/" + V2 + "/_search", Json.map("from", 9980, "size", 20));
            atLimitOk = true;
        } catch (EsException e) {
            atLimitOk = false;
            limitError = e.getMessage();
        }
        try {
            es.post("/" + V2 + "/_search", Json.map("from", 9990, "size", 20));
        } catch (EsException e) {
            if (limitError.isEmpty()) limitError = e.getMessage();
        }

        double base = results.get(0).median();
        double last = results.get(results.size() - 1).median();
        double ratio = base > 0 ? last / base : 0;
        boolean amplified = base > 0 && ratio >= 1.5;
        String baseText = base <= 0 ? "<1" : num(base);

        List<BarChartData.Item> chartItems = new ArrayList<>();
        List<Object> sampleRows = new ArrayList<>();
        for (PageSample r : results) {
            chartItems.add(new BarChartData.Item("from=" + r.from(), r.median()));
            sampleRows.add(Json.map("from", r.from(), "median", r.median(), "samples", r.samples()));
        }

        // 结论必须由本轮实测决定：这台机器上 2 万条 / 3 分片时，from=9000 与 from=0 的 took
        // 常常只差 1–3ms，落在 1ms 分辨率与缓存抖动里。写死「放大 N 倍」会在实测反序时自相矛盾。
        String verdict = amplified
                ? "第 1 页 " + baseText + "ms，第 450 页（from=9000）" + num(last) + "ms，放大 "
                        + String.format(Locale.ROOT, "%.1f", ratio)
                        + " 倍——深翻页的成本不是\"多传了 20 条\"，而是每个分片多吐了 9000 条"
                : "第 1 页 " + baseText + "ms，第 450 页（from=9000）" + num(last) + "ms，两者只差 "
                        + num(Math.abs(last - base)) + "ms —— 落在 took 的 1ms 分辨率与缓存抖动里，"
                        + "本机 2 万条看不出明显放大；机制上每个分片仍要吐 from+size 条候选，数据量上去才会显现";

        return LevelResult.builder(verdict)
                .metrics(
                        new Metric("from=0", baseText + " ms", "3 轮中位 · took；0 表示 <1ms"),
                        new Metric("from=9000", num(last) + " ms", "3 轮中位 · took"),
                        new Metric("放大倍数",
                                amplified ? String.format(Locale.ROOT, "%.1f", ratio) + "×" : "未测出（噪声内）",
                                "本轮实测；不足 1.5 倍就不下结论"),
                        new Metric("9980+20=10000", atLimitOk ? "通过" : "异常被拒", "上限是小于等于"),
                        new Metric("9990+20=10010", limitError.isEmpty() ? "未触发（异常）" : "被拒绝",
                                "index.max_result_window"))
                .chart(new BarChartData(chartItems, "ms（中位数）", "3 轮采样取中位数；采样前 from=0/9000 各预热一次，不计入样本"))
                .json(Json.map("samples", sampleRows, "limitError", limitError,
                        "amplified", amplified, "ratio", ratio))
                .notes(
                        "3 分片 × (from+size) 条候选参与堆排序，这是深翻页的机制成本；分片越多，放大越明显 —— "
                                + "但机制成立不等于本机能测出来，2 万条的数据量下差异只有几毫秒。",
                        atLimitOk
                                ? "9980+20 恰好等于 10000，通过 —— 上限是「小于等于」，卡在边界不报错。"
                                : "9980+20 本应等于 10000 却被拒，先检查 index.max_result_window 是否被改过。",
                        limitError.isEmpty()
                                ? "9990+20 本应越界却没报错，属异常，请检查 max_result_window。"
                                : "再往前 10 条（9990+20=10010）就被拒了，报错原文：" + limitError,
                        "不要把 max_result_window 调大当作解决方案：那只是把崩溃点往后移，内存与 CPU 压力同比上涨。",
                        "真正的解是 search_after（下一关），它用\"上一页最后一条的排序值\"作为游标，不受 from 影响。")
                .build();
    }

    /** 只取 ES 自己报告的 took（毫秒），不含网络往返 */
    private double page(int from) {
        return es.post("/" + V2 + "/_search", Json.map(
                "from", from,
                "size", 20,
                "track_total_hits", false,
                "sort", Json.list(Json.map("price", "asc"), Json.map("sku", "asc")),
                "_source", Json.list("sku", "title", "price"))).path("took").asDouble();
    }

    /** 与 JS 的 number 打印保持一致：整数值不带小数点，否则保留原有小数位 */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private record PageSample(int from, double median, List<Double> samples) {
    }
}
