package com.eslab.model;

import java.util.List;

/**
 * 一个关卡的后端实现契约。
 *
 * 每关一个实现类（com.eslab.levels.M11DynamicMapping 等），
 * 页面上的「查看本关后端实现」直接回读这个类的源码，
 * 所以「页面执行的那次请求」与「你看到的那段代码」永远是同一份文件。
 */
public interface LevelModule {

    /** 关卡 id，与 knowledge/modules.json 中的 id 一致，例如 m1-1 */
    String id();

    LevelResult run() throws Exception;

    /**
     * 本关开始前需要的别名基线。
     *
     * 别名 eslab-goods 是全局可变状态：m8-2 切 v4、m9-6 切 v6。如果每关都不声明，
     * 「切换前指向哪儿」就取决于学员之前跑过哪些关 —— 表格里写的和实际读到的会不一致。
     *
     * 声明之后，LevelRunner 会在 run() 之前把别名幂等归位到指定目标；
     * 默认返回空表示本关自己管理（例如 m8-1 要演示完整的切换过程）。
     */
    default List<AliasBaseline> aliasBaselines() {
        return List.of();
    }
}
