package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.AliasBaseline;
import com.eslab.model.LevelModule;
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
 *
 * 本关额外做一次正面对照：同一条规则放在**查询期**（v6）与**索引期**（syn-idx），
 * 两条路都能查到，但「改规则要付什么代价」完全不同 —— 索引期要重灌 2 万条，
 * 查询期只切别名；想连别名都不切，则必须把规则放到文件里再 _reload_search_analyzers。
 */
@Component
public class M96Synonym implements LevelModule {

    private static final String ALIAS = LabIndexes.ALIAS;
    private static final String V4 = LabIndexes.V4;
    private static final String V6 = LabIndexes.V6;
    private static final String SYN_IDX = LabIndexes.SYN_IDX;

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
        Object bluetooth = Json.map("match", Json.map("title", "蓝牙耳机"));
        long before = lab.countWithQuery(ALIAS, tws);

        // @focus-start 新分析器只放在查询期：索引期仍用 ik_max_word，标题内容一个字都不用改
        // v6 的 reindex 源是别名本身（登记为 REINDEX_ALIAS），所以上面的归位决定了搬的是哪一份数据
        long t0 = System.currentTimeMillis();
        lab.ensureByReindex(V6);
        long v6Ms = System.currentTimeMillis() - t0;

        JsonNode idxAnalyze = es.post("/" + V6 + "/_analyze", Json.map("field", "title", "text", "TWS"));
        JsonNode searchAnalyze = es.post("/" + V6 + "/_analyze", Json.map(
                "analyzer", "goods_syn_search", "text", "TWS"));
        // @focus-end

        // @focus-start 对照：同一条规则放索引期，代价是整份数据重灌
        long t1 = System.currentTimeMillis();
        LabData.Ensure synIdx = lab.ensureByReindex(SYN_IDX);
        long synIdxMs = System.currentTimeMillis() - t1;
        JsonNode synIdxAnalyze = es.post("/" + SYN_IDX + "/_analyze", Json.map("field", "title", "text", "TWS"));
        // @focus-end

        long v6Tws = lab.countWithQuery(V6, tws);
        long v6Bt = lab.countWithQuery(V6, bluetooth);
        long idxTws = lab.countWithQuery(SYN_IDX, tws);
        long idxBt = lab.countWithQuery(SYN_IDX, bluetooth);

        // @focus-start 想「连别名都不切」：只有文件式规则才能 reload；内联规则 reload 是空操作
        JsonNode reload = es.post("/" + V6 + "/_reload_search_analyzers", null);
        int reloadDetails = reload.path("reload_details").size();
        // @focus-end

        // @focus-start 一次请求把别名从 v4 换到 v6，客户端不需要知道发生了变更
        LabData.Swap swap = lab.swapAlias(ALIAS, V6);
        // @focus-end

        long after = lab.countWithQuery(ALIAS, tws);
        String newTarget = lab.writeTargetOf(ALIAS);

        List<String> searchTokens = tokens(searchAnalyze);
        List<String> idxTokens = tokens(idxAnalyze);
        List<String> synIdxTokens = tokens(synIdxAnalyze);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("同义词放在哪", "查询期（search_analyzer）", "索引期（analyzer）"));
        rows.add(TableData.row("field=title 的分析结果（TWS）",
                String.join(" / ", idxTokens), String.join(" / ", synIdxTokens)));
        rows.add(TableData.row("查询期分析器的结果（TWS）", String.join(" / ", searchTokens), "—"));
        rows.add(TableData.row("查询 TWS 命中", fmt(v6Tws), fmt(idxTws)));
        rows.add(TableData.row("查询「蓝牙耳机」命中", fmt(v6Bt), fmt(idxBt)));
        rows.add(TableData.row("改规则要付的代价", "重建索引 + 切别名（" + v6Ms + "ms）",
                "重建索引 + 切别名（" + synIdxMs + "ms，搬 " + fmt(synIdx.docs()) + " 条）"));
        rows.add(TableData.row("能否只 reload 不重建", reloadDetails + " 条生效（内联规则无效）", "不能"));
        rows.add(TableData.row("别名指向", beforeTarget + " → " + newTarget, "不挂别名（对照用）"));

        return LevelResult.builder("「TWS」在切换前命中 " + fmt(before) + " 条，切到带 synonym_graph 的 v6 之后 "
                        + fmt(after) + " 条；索引期方案（" + SYN_IDX + "）同样能查到 " + fmt(idxTws)
                        + " 条，但它要重灌 " + fmt(synIdx.docs()) + " 条数据。查「蓝牙耳机」时两者不同：查询期 "
                        + fmt(v6Bt) + " 条 vs 索引期 " + fmt(idxBt) + " 条 —— 索引期把整组同义词都写进了倒排")
                .metrics(
                        new Metric("查询期展开词项", String.join(" / ", searchTokens), "goods_syn_search"),
                        new Metric("索引期展开词项", String.join(" / ", synIdxTokens), "goods_syn_index"),
                        new Metric("切换前命中", fmt(before),
                                "别名指向 " + (beforeTarget == null ? "（无）" : beforeTarget)),
                        new Metric("切换后命中", fmt(after), "别名指向 " + V6),
                        new Metric("查 TWS：查询期 / 索引期", fmt(v6Tws) + " / " + fmt(idxTws),
                                "同一条同义词规则，两条路都能召回"),
                        new Metric("查「蓝牙耳机」：查询期 / 索引期", fmt(v6Bt) + " / " + fmt(idxBt),
                                "索引期把整组同义词写进倒排，命中更多"),
                        new Metric("重建成本", "v6 复用（" + v6Ms + "ms）/ syn-idx 新建 " + synIdxMs + "ms",
                                "新建要搬 " + fmt(synIdx.docs()) + " 条"),
                        new Metric("_reload 生效条数", reloadDetails,
                                "内联规则 reload 是空操作，只能重建"))
                .table(TableData.of(List.of("检查项", "v6（查询期）", "syn-idx（索引期）"), rows))
                .json(Json.map("tokens", searchTokens, "idxTokens", idxTokens, "synIdxTokens", synIdxTokens,
                        "before", before, "after", after, "v6Tws", v6Tws, "idxTws", idxTws,
                        "v6Ms", v6Ms, "synIdxMs", synIdxMs,
                        "beforeTarget", beforeTarget == null ? "" : beforeTarget,
                        "repinned", repinned, "swapErrors", swap.errors()))
                .notes(
                        "synonym_graph 是多词同义词：它会把「TWS」展开成蓝牙耳机/真无线等一组词项，再分别去倒排里查",
                        "查「TWS」时两条路一致（" + fmt(v6Tws) + " = " + fmt(idxTws) + "），但查「蓝牙耳机」不一致："
                                + "查询期是「把查询串展开后去匹配原词项」（" + fmt(v6Bt) + " 条），"
                                + "索引期是「写入时就把整组同义词都塞进倒排」（" + fmt(idxBt) + " 条）—— 后者召回更宽，索引也更大",
                        "真正的差别是「改规则要不要重灌数据」—— 索引期改一次就要 reindex 一次；查询期用文件式 synonyms_path "
                                + "再 _reload_search_analyzers 可以只重载（本机内联规则，reload 生效 " + reloadDetails + " 条，等于没有）",
                        "本关的 synonyms 是内联在 settings 里的，所以改它仍然要建新索引；要免重灌就得把规则放到文件里",
                        "同义词上线 = 建新索引 + reindex + 原子切别名，这三步就是第 8 章那条流水线")
                .build();
    }

    private static List<String> tokens(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : d.path("tokens")) out.add(t.path("token").asText());
        return out;
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
