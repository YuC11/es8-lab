package com.eslab.model;

import java.util.ArrayList;
import java.util.List;

/** 柱状图数据：用于「同一指标的多档对比」，例如不同 from 的耗时曲线 */
public record BarChartData(List<Item> items, String unit, String note) {

    public record Item(String label, double value) {
    }

    public static BarChartData of(String unit, String note, Item... items) {
        return new BarChartData(new ArrayList<>(List.of(items)), unit, note);
    }
}
