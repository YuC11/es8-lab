package com.eslab.levels;

import com.eslab.model.LevelModule;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 关卡注册表：Spring 注入所有 LevelModule 实现，按 id 索引 */
@Component
public class LevelRegistry {

    /**
     * 按「章号 + 关号」数值排序，而不是字符串排序。
     *
     * 字符串排序会把 m10-1 排在 m2-1 前面（'1' < '2'），课程顺序看起来像坏的。
     * 只影响 /api/health 里 levels 的顺序 —— 页面的章节顺序来自 modules.json 的数组。
     */
    private static final Comparator<LevelModule> ORDER = Comparator
            .comparingInt((LevelModule m) -> key(m.id())[0])
            .thenComparingInt(m -> key(m.id())[1]);

    private static int[] key(String id) {
        String s = id.startsWith("m") ? id.substring(1) : id;
        String[] parts = s.split("-");
        try {
            int chapter = Integer.parseInt(parts[0]);
            int level = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return new int[]{chapter, level};
        } catch (NumberFormatException e) {
            return new int[]{Integer.MAX_VALUE, 0};
        }
    }

    private final Map<String, LevelModule> byId = new LinkedHashMap<>();

    public LevelRegistry(Collection<LevelModule> modules) {
        modules.stream()
                .sorted(ORDER)
                .forEach(m -> byId.put(m.id(), m));
    }

    public LevelModule get(String id) {
        return byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public Collection<String> ids() {
        return byId.keySet();
    }

    /**
     * 关卡 id 对应的 Java 源文件相对路径，供页面回读实现。
     *
     * 路径相对「仓库根」，所以带 backend/ 前缀：后端是 backend/ 下的独立 Maven 项目，
     * 而源码回读要跨越项目边界去读磁盘上的真实文件。
     */
    public String sourceFileOf(String id) {
        LevelModule m = byId.get(id);
        if (m == null) return null;
        return "backend/src/main/java/" + m.getClass().getName().replace('.', '/') + ".java";
    }
}
