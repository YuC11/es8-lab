package com.eslab.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 表格结果：列为表头，行是单元格数组（字符串或数字） */
public record TableData(List<String> columns, List<List<Object>> rows) {

    public static List<Object> row(Object... cells) {
        return new ArrayList<>(Arrays.asList(cells));
    }

    public static TableData of(List<String> columns, List<List<Object>> rows) {
        return new TableData(columns, rows);
    }
}
