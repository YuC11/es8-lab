package com.eslab.model;

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
}
