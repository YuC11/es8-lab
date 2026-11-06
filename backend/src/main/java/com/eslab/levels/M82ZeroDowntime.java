package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.AliasBaseline;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m8-2：零停机改 Mapping。
 *
 * 核心不是 reindex，而是「一次请求同时 remove + add」——
 * 别名切换在集群状态里是一次原子更新，客户端不会看到「别名指向空索引」的中间态。
 */
@Component
public class M82ZeroDowntime implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M82ZeroDowntime(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-2";
    }

    /** 本关起点固定 v3：旋转前后两个状态都可读，结果与执行顺序无关 */
    @Override
    public List<AliasBaseline> aliasBaselines() {
        return List.of(new AliasBaseline(LabIndexes.ALIAS, LabIndexes.V3));
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 新 Mapping 只能写进新索引：v4 多了 selling_point，改了 title.keyword.ignore_above
        // 定义与目标条数登记在 LabIndexes，条数不符会整条重建（不是补差额）
        lab.ensureByReindex(LabIndexes.V3);
        LabData.Ensure v4 = lab.ensureByReindex(LabIndexes.V4);
        // @focus-end

        // @focus-start 切换前必须停在 v3：上一关或手工操作可能把指针带到别处
        String priorTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        boolean repinned = !LabIndexes.V3.equals(priorTarget);
        if (repinned) {
            lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V3);
        }
        long beforeCount = lab.countQuietly(LabIndexes.ALIAS);
        String beforeTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        // @focus-end

        // @focus-start 切别名：remove 与 add 在同一次请求里，没有中间态
        LabData.Swap swap = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V4);
        // @focus-end

        long afterCount = lab.countQuietly(LabIndexes.ALIAS);
        String afterTarget = lab.writeTargetOf(LabIndexes.ALIAS);

        List<List<Object>> table = new ArrayList<>();
        table.add(TableData.row("字段数", fieldCount(LabIndexes.V3), fieldCount(LabIndexes.V4)));
        table.add(TableData.row("title.keyword.ignore_above",
                ignoreAbove(LabIndexes.V3), ignoreAbove(LabIndexes.V4)));
        table.add(TableData.row("selling_point", hasField(LabIndexes.V3, "selling_point") ? "有" : "无",
                hasField(LabIndexes.V4, "selling_point") ? "有" : "无"));
        table.add(TableData.row("经别名命中", beforeCount < 0 ? "别名还不存在" : fmt(beforeCount),
                fmt(afterCount)));

        int actions = swap.actions();
        String actionHint = actions >= 2 ? "remove + add 同一次请求" : "已指向 v4，只需一次幂等 add";

        return LevelResult.builder("v4 建好、数据搬完，再用一次 /_aliases 把指针从 "
                        + (beforeTarget == null ? "（无别名）" : beforeTarget) + " 换到 " + afterTarget
                        + "，切换前后命中数不变"
                        + (repinned ? "（进入本关时指针不在 v3，已先归位）" : ""))
                .metrics(
                        new Metric("v4 文档数", fmt(v4.docs()),
                                v4.shortfall() ? "源不足，未达 2 万" : (v4.created() ? "本次重建" : "已达标复用")),
                        new Metric("切换动作", actions + " 个", actionHint),
                        new Metric("切换前命中", beforeCount < 0 ? "—" : fmt(beforeCount),
                                beforeTarget == null ? "别名还不存在" : "指向 " + beforeTarget),
                        new Metric("切换后命中", fmt(afterCount),
                                swap.errors() ? "errors=true，看原始响应" : "errors=false"))
                .table(TableData.of(List.of("对比项", "v3（旧）", "v4（新）"), table))
                .json(Map.of("swap", swap.raw(), "v4", v4, "repinned", repinned))
                .notes(
                        "Mapping 的两个部分都不能原地改：新增字段不行，把 ignore_above 从 256 改成 128 也不行",
                        "同一次请求里的 remove + add 由集群状态更新一次生效，所以不存在「别名查不到数据」的瞬间",
                        "本关的起点固定是 v3：进入时如果指针在别处（比如上一轮跑过 m9-6），会先归位再演示切换，"
                                + "所以表格里的「切换前」永远是真实值，不依赖你之前跑过哪些关")
                .build();
    }

    private long countOf(String indexOrAlias) {
        try {
            return es.get("/" + indexOrAlias + "/_count").path("count").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private int fieldCount(String index) {
        try {
            return mapping(index).path("properties").size();
        } catch (Exception e) {
            return -1;
        }
    }

    private int ignoreAbove(String index) {
        try {
            return mapping(index).path("properties").path("title")
                    .path("fields").path("keyword").path("ignore_above").asInt(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean hasField(String index, String field) {
        try {
            return mapping(index).path("properties").has(field);
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode mapping(String index) {
        return es.get("/" + index + "/_mapping").path(index).path("mappings");
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
