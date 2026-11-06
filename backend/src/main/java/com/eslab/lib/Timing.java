package com.eslab.lib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 计时工具：单机单次 took 抖动极大，任何「优化前后」的结论都必须多次采样取中位数。
 * 这是本项目所有性能结论的计量基础 —— 只跑一次得到的数字不作为证据。
 */
public final class Timing {
    private Timing() {
    }

    public record Sample(List<Double> runs, double median, double min, double p95, double mean, int rounds) {
    }

    public record Compare(double baseMedian, double optimizedMedian, double deltaMs,
                          double speedup, double improvementPct) {
    }

    @FunctionalInterface
    public interface RoundTask {
        /** @param round 轮次；-1 表示预热轮 */
        double run(int round) throws Exception;
    }

    public static Sample sample(int rounds, int warmup, RoundTask task) throws Exception {
        for (int i = 0; i < warmup; i++) task.run(-1 - i);
        List<Double> runs = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) runs.add(task.run(i));
        return summarize(runs);
    }

    public static Sample sample(int rounds, RoundTask task) throws Exception {
        return sample(rounds, 1, task);
    }

    /** A/B 两组的采样结果，附带本轮使用的口径，便于页面标注 */
    public record Pair(Sample a, Sample b, int rounds, int warmup) {
        public String scope() {
            return "每侧 " + rounds + " 轮 + " + warmup + " 轮预热，A/B 交替执行";
        }
    }

    /**
     * A/B 交替采样。
     *
     * 先各自预热 warmup 轮（把缓存、连接、JIT 预热掉），再交替取正式样本：
     * 奇数轮先跑 A，偶数轮先跑 B。这样两组经历的环境顺序基本对称，
     * 可以抵消「第二组总是更热」这一类系统性漂移 —— 分开跑 A 全集再跑 B 全集做不到这一点。
     */
    public static Pair pair(int rounds, int warmup, RoundTask a, RoundTask b) throws Exception {
        for (int i = 0; i < warmup; i++) {
            a.run(-1 - i);
            b.run(-1 - i);
        }
        List<Double> ra = new ArrayList<>(rounds);
        List<Double> rb = new ArrayList<>(rounds);
        for (int i = 0; i < rounds; i++) {
            if (i % 2 == 0) {
                ra.add(a.run(i));
                rb.add(b.run(i));
            } else {
                rb.add(b.run(i));
                ra.add(a.run(i));
            }
        }
        return new Pair(summarize(ra), summarize(rb), rounds, warmup);
    }

    public static Sample summarize(List<Double> raw) {
        List<Double> sorted = new ArrayList<>(raw);
        Collections.sort(sorted);
        double median;
        int n = sorted.size();
        if (n == 0) median = 0;
        else if (n % 2 == 1) median = sorted.get((n - 1) / 2);
        else median = (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;

        double sum = 0;
        for (double v : raw) sum += v;

        return new Sample(
                List.copyOf(raw),
                round(median),
                round(sorted.isEmpty() ? 0 : sorted.get(0)),
                round(percentile(sorted, 95)),
                round(n == 0 ? 0 : sum / n),
                n);
    }

    private static double percentile(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil((p / 100.0) * sorted.size()) - 1));
        return sorted.get(idx);
    }

    public static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    public static double round(double v) {
        return round(v, 1);
    }

    /** 两段耗时对比：正数 improvementPct 表示变得更慢（base 更大），语义与前端一致 */
    public static Compare compare(Sample base, Sample optimized) {
        double b = base.median();
        double o = optimized.median();
        double delta = round(b - o);
        double speedup = o > 0 ? round(b / o, 2) : 0;
        double pct = b > 0 ? round(((b - o) / b) * 100) : 0;
        return new Compare(b, o, delta, speedup, pct);
    }
}
