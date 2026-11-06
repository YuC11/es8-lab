package com.eslab.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 关卡后端的统一返回结构：前端只认这个形状。
 *
 * summary 是「一句话结论」，页面顶部大字展示；
 * json 是原始 ES 响应，默认折叠，便于对照「表格里的数字来自响应哪一段」。
 */
public record LevelResult(
        String summary,
        List<Metric> metrics,
        TableData table,
        BarChartData chart,
        Object json,
        List<String> notes) {

    public static Builder builder(String summary) {
        return new Builder(summary);
    }

    public static final class Builder {
        private final String summary;
        private List<Metric> metrics;
        private TableData table;
        private BarChartData chart;
        private Object json;
        private List<String> notes;

        private Builder(String summary) {
            this.summary = summary;
        }

        public Builder metrics(Metric... items) {
            this.metrics = new ArrayList<>(Arrays.asList(items));
            return this;
        }

        public Builder metrics(List<Metric> items) {
            this.metrics = items;
            return this;
        }

        public Builder table(TableData value) {
            this.table = value;
            return this;
        }

        public Builder chart(BarChartData value) {
            this.chart = value;
            return this;
        }

        public Builder json(Object value) {
            this.json = value;
            return this;
        }

        public Builder notes(String... items) {
            this.notes = new ArrayList<>(Arrays.asList(items));
            return this;
        }

        public Builder notes(List<String> items) {
            this.notes = items;
            return this;
        }

        public LevelResult build() {
            return new LevelResult(summary, metrics, table, chart, json, notes);
        }
    }
}
