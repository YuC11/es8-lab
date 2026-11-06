package com.eslab.model;

/**
 * 关卡运行前需要把某个别名归位到某个索引。
 *
 * 只表达「我需要什么」，不负责执行 —— 归位由 LevelRunner 调用 LabData 完成。
 */
public record AliasBaseline(String alias, String target) {
}
