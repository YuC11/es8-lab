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
