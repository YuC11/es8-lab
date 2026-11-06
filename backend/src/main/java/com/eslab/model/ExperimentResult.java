package com.eslab.model;

import java.util.List;

/** 一个 A/B 实验的结果：两侧中位数 + 每轮样本 + 结论 + 适用边界 */
public record ExperimentResult(
        String id,
        String title,
        String question,
        Side a,
        Side b,
        Delta delta,
        String conclusion,
        List<String> caveats) {

    public record Side(String label, double value, String unit, List<Double> samples, String detail) {
    }

    public record Delta(double speedup, double improvementPct) {
    }
}
