package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m8-1：别名是什么。
 *
 * 这一关只做一件事：把「应用看到的索引名」和「真实的物理索引」拆开。
 * 后面的零停机、同义词上线都建立在它上面 —— 没有别名，任何 mapping 变更都要停机。
 *
 * 别名是全局可变状态，所以本关自己管三段：先归位到 v3，再切 v4，最后复原 v3。
 * 这样无论学员之前跑过哪些关，本关的表格和结束状态都是确定的。
 */
@Component
public class M81Alias implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M81Alias(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 建 v3 并把 v2 的数据搬过去（v3 与 v2 同构，只是换了个名字）
        LabData.Ensure v3 = lab.ensureByReindex(LabIndexes.V3);
        // @focus-end

        // @focus-start 切换前先把现场记下来：可能是 v4/v6，也可能从来没有别名
        List<Map<String, Object>> before = lab.aliasRows(LabIndexes.ALIAS);
        String beforeTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        // @focus-end

        // @focus-start 第一步：把别名归位到 v3（幂等；已经指向 v3 时动作数只有一个 add）
        LabData.Swap pin = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V3);
        String pinnedTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        long viaPin = lab.countQuietly(LabIndexes.ALIAS);
        // @focus-end

        // @focus-start 第二步：建 v4，做一次真实的切换 —— 应用侧一个字都不用改
        LabData.Ensure v4 = lab.ensureByReindex(LabIndexes.V4);
        LabData.Swap toV4 = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V4);
        String v4Target = lab.writeTargetOf(LabIndexes.ALIAS);
        long viaV4 = lab.countQuietly(LabIndexes.ALIAS);

        // 第三步：复原到 v3，保证下一关（m8-2）的起点是确定的
        LabData.Swap back = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V3);
        String endTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        long viaEnd = lab.countQuietly(LabIndexes.ALIAS);
        // @focus-end

        List<List<Object>> table = new ArrayList<>();
        table.add(TableData.row("归位前", beforeTarget == null ? "（无别名）" : beforeTarget,
                before.isEmpty() ? "0 条" : fmt(countQuietly(beforeTarget)), "—"));
        table.add(TableData.row("归位到 v3", pinnedTarget, fmt(viaPin), pin.actions() + " 个动作"));
        table.add(TableData.row("切到 v4", v4Target, fmt(viaV4), toV4.actions() + " 个动作"));
        table.add(TableData.row("复原 v3", endTarget, fmt(viaEnd), back.actions() + " 个动作"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("before", before);
        json.put("stages", List.of(pin.raw(), toV4.raw(), back.raw()));
        json.put("v3", v3);
        json.put("v4", v4);

        return LevelResult.builder("别名 " + LabIndexes.ALIAS + " 依次经历 "
                        + (beforeTarget == null ? "（无）" : beforeTarget) + " → " + pinnedTarget
                        + " → " + v4Target + " → " + endTarget
                        + "；应用只认别名，换索引时不用改代码")
                .metrics(
                        new Metric("v3 文档数", fmt(v3.docs()),
                                v3.shortfall() ? "源不足，未达 2 万" : "直连物理索引"),
                        new Metric("经别名查询", fmt(viaV4), "切到 v4 后同一条查询"),
                        new Metric("切换动作", toV4.actions() + " 个",
                                "remove 旧指向 + add 新指向，同一次请求"),
                        new Metric("本关结束指向", endTarget == null ? "无" : endTarget,
                                "固定 v3，下一关从这里切 v4"))
                .table(TableData.of(List.of("阶段", "别名指向", "经别名查询", "本次动作"), table))
                .json(json)
                .notes(
                        "别名不是索引的副本，只是一层指向关系：写 " + LabIndexes.ALIAS
                                + " 的文档会落到当前写目标上，读它等于读那个索引",
                        "本关自己管三段（归位 v3 → 切 v4 → 复原 v3），所以无论你先跑过哪些关，"
                                + "表格里的「归位前」都是真实读到的值，结束状态都是 v3",
                        "归位与切换都是同一条 /_aliases 请求：remove 旧指向 + add 新指向，客户端看不到中间态",
                        "文档数在 v3/v4 上完全一样（都是 2 万条、同一份数据），所以 _count 区分不出别名指向谁 —— "
                                + "判断指向必须看 _cat/aliases，这也是本关把「别名指向」单列成指标的原因")
                .build();
    }

    /** 展示用：指向的索引可能刚被删掉，读不到就给「—」 */
    private long countQuietly(String index) {
        return lab.countQuietly(index);
    }

    private static String fmt(long n) {
        if (n < 0) return "—";
        return String.format(Locale.ROOT, "%,d", n);
    }
}
