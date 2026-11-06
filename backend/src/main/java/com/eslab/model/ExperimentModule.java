package com.eslab.model;

/**
 * 一个 A/B 实验的契约。
 *
 * 实验与关卡的区别：关卡回答「这是什么」，实验回答「改了这一处，代价变了多少」。
 * 实验中任何数字都必须多次采样取中位数，否则单次 took 的抖动会被当成结论。
 */
public interface ExperimentModule {

    String id();

    String title();

    String question();

    ExperimentResult run() throws Exception;
}
