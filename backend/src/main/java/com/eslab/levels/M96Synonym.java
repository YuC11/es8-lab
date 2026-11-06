package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
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
 * m9-6：同义词上线。
 *
 * 这一关把第 8 章的三步连起来：建新索引（新分析器）→ reindex 搬数据 → 原子切别名。
 * 它也是「为什么需要别名」最实在的答案：同义词迭代一次，就要走一遍这条流水线。
 */
@Component
public class M96Synonym implements LevelModule {

    private static final String ALIAS = LabIndexes.ALIAS;
    private static final String V4 = LabIndexes.V4;
    private static final String V6 = LabIndexes.V6;
    private static final int DOCS = LabIndexes.DOCS;

    private final EsClient es;
    private final LabData lab;

    public M96Synonym(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-6";
    }

    /** 本关起点固定 v4：没有同义词的版本，才能看到「切换前 0 条」 */
    @Override
    public List<AliasBaseline> aliasBaselines() {
        return List.of(new AliasBaseline(LabIndexes.ALIAS, LabIndexes.V4));
    }

    @Override
    public LevelResult run() throws Exception {
        // 准备 v4（别名当前该指向的「没有同义词」版本）
        lab.ensureByReindex(V4);

        // @focus-start 基线固定 v4：上一关可能把指针带到 v6 或 v3，不归位就没法重复看「切换前 0 条」
        String target = lab.writeTargetOf(ALIAS);
        boolean repinned = !V4.equals(target);
        if (repinned) {
            lab.swapAlias(ALIAS, V4);
        }
        String beforeTarget = lab.writeTargetOf(ALIAS);
        // @focus-end

        Object tws = Json.map("match", Json.map("title", "TWS"));
        long before = lab.countWithQuery(ALIAS, tws);

        // @focus-start 新分析器只放在查询期：索引期仍用 ik_max_word，标题内容一个字都不用改
        // v6 的 reindex 源是别名本身（登记为 REINDEX_ALIAS），所以上面的归位决定了搬的是哪一份数据
        lab.ensureByReindex(V6);
        JsonNode analyze = es.post("/" + V6 + "/_analyze", Json.map(
                "analyzer", "goods_syn_search",
                "text", "TWS"));
        // @focus-end

        List<String> tokens = new ArrayList<>();
        for (JsonNode t : analyze.path("tokens")) tokens.add(t.path("token").asText());

        // @focus-start 一次请求把别名从 v4 换到 v6，客户端不需要知道发生了变更
        LabData.Swap swap = lab.swapAlias(ALIAS, V6);
        // @focus-end

        long after = lab.countWithQuery(ALIAS, tws);
        String newTarget = lab.writeTargetOf(ALIAS);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("查询词 TWS 的分析结果", "索引期 ik_max_word", "查询期 goods_syn_search"));
        rows.add(TableData.row("展开出的词项", "tws", String.join(" / ", tokens)));
        rows.add(TableData.row("match title=TWS", fmt(before) + " 条", fmt(after) + " 条"));
        rows.add(TableData.row("别名指向",
                beforeTarget == null ? "（无别名）" : beforeTarget + (repinned ? "（本关归位）" : ""),
                newTarget));

        return LevelResult.builder("「TWS」在切换前命中 " + fmt(before) + " 条，切到带 synonym_graph 的 v6 之后 "
                        + fmt(after) + " 条；别名一次切换完成，客户端查询语句一个字都没改")
                .metrics(
                        new Metric("展开词项", tokens.size(), String.join(" / ", tokens)),
                        new Metric("切换前命中", fmt(before),
                                "别名指向 " + (beforeTarget == null ? "（无）" : beforeTarget)),
                        new Metric("切换后命中", fmt(after), "别名指向 " + V6),
                        new Metric("别名动作", swap.actions(), swap.errors() ? "errors=true" : "errors=false"))
                .table(TableData.of(List.of("检查项", "切换前", "切换后"), rows))
                .json(Map.of("tokens", tokens, "before", before, "after", after,
                        "beforeTarget", beforeTarget == null ? "" : beforeTarget,
                        "repinned", repinned, "swapErrors", swap.errors()))
                .notes(
                        "synonym_graph 是多词同义词：它会把「TWS」展开成蓝牙耳机/真无线等一组词项，再分别去倒排里查",
                        "实测：把它放进索引期分析器也不会报错、同样会展开。真正的区别是「改规则要不要重灌数据」—— " +
                                "索引期改一次就要 reindex 一次，查询期用 synonyms_path + _reload_search_analyzers 可以只重载",
                        "本关的 synonyms 是内联在 settings 里的，所以改它仍然要建新索引；要免重灌就得把规则放到文件里",
                        "同义词上线 = 建新索引 + reindex + 原子切别名，这三步就是第 8 章那条流水线")
                .build();
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
