package com.eslab.model;

/** 结果卡片上的一个指标：值可以是数字或字符串 */
public record Metric(String label, Object value, String hint) {
    public static Metric of(String label, Object value) {
        return new Metric(label, value, null);
    }
}
