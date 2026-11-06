package com.eslab.lib;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 构造请求体的小工具。
 *
 * 用 Json.map("size", 0, "query", Json.map("term", Json.map("tags", "数码")))
 * 比显式 new ObjectNode 更接近 curl 里手写 JSON 的直觉，方便学习者对照。
 */
public final class Json {
    private Json() {
    }

    /** 交替传入 key/value，返回有序 Map（Jackson 序列化为 JSON 对象） */
    public static Map<String, Object> map(Object... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Json.map 需要偶数个参数（key/value 交替）");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static List<Object> list(Object... items) {
        List<Object> l = new ArrayList<>(items.length);
        for (Object o : items) l.add(o);
        return l;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> copy(Map<String, Object> src) {
        return new LinkedHashMap<>(src);
    }
}
