package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
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
 * m9-2：短语、顺序与前缀。
 *
 * match 是「词都出现就行」，match_phrase 是「词要按顺序相邻」，
 * slop 是「允许隔几个词」—— 三者召回依次收紧，语义依次精确。
 */
@Component
public class M92PhrasePrefix implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;

    private final EsClient es;
    private final LabData lab;

    public M92PhrasePrefix(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-2";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.ensureSearch();

        // @focus-start 四个梯度：只要词出现 / 要相邻 / 允许间隔 0 个词 / 允许间隔 3 个词
        Probe m = probe("match",
                Json.map("match", Json.map("title", "蓝牙耳机")), "词出现即可，不考虑顺序");
        Probe p = probe("match_phrase",
                Json.map("match_phrase", Json.map("title", "蓝牙耳机")), "必须相邻且顺序一致");
        Probe s0 = probe("phrase(降噪 蓝牙) slop 0",
                Json.map("match_phrase", Json.map("title", Json.map("query", "降噪 蓝牙", "slop", 0))),
                "顺序反了就查不到");
        Probe s3 = probe("phrase(降噪 蓝牙) slop 3",
                Json.map("match_phrase", Json.map("title", Json.map("query", "降噪 蓝牙", "slop", 3))),
                "允许中间隔 3 个词");
        // @focus-end

        Probe pre = probe("prefix title.keyword",
                Json.map("prefix", Json.map("title.keyword", "小米")), "前缀已知，可用倒排剪枝");
        Probe wild = probe("wildcard *耳机*",
                Json.map("wildcard", Json.map("title.keyword", "*耳机*")), "前导通配，无法剪枝");

        List<List<Object>> rows = new ArrayList<>();
        for (Probe x : List.of(m, p, s0, s3, pre, wild)) {
            rows.add(TableData.row(x.label, fmt(x.total), x.took + "ms", x.note));
        }

        return LevelResult.builder("「蓝牙耳机」match 命中 " + fmt(m.total) + "，「必须相邻」后剩 "
                        + fmt(p.total) + "；把词序颠倒再来一次，slop 0 只有 " + fmt(s0.total)
                        + "，放宽到 slop 3 回到 " + fmt(s3.total))
                .metrics(
                        new Metric("match", fmt(m.total) + " 条", "词出现即可"),
                        new Metric("match_phrase", fmt(p.total) + " 条", "必须相邻同序"),
                        new Metric("slop 0 vs 3", fmt(s0.total) + " → " + fmt(s3.total), "放宽间隔的代价"),
                        new Metric("prefix", fmt(pre.total) + " 条 · " + pre.took + "ms", "可用倒排前缀"),
                        new Metric("wildcard", fmt(wild.total) + " 条 · " + wild.took + "ms", "前导通配要扫全词典"))
                .table(TableData.of(List.of("查询", "命中", "took", "说明"), rows))
                .json(Map.of("search", SEARCH))
                .notes(
                        "match_phrase 要求词项位置连续；slop 是「允许移动几次位置」，不是「隔几个字符」",
                        "在本机 2 万条规模下，wildcard 与 prefix 的 took 差异多半落在噪声里 —— 前导通配的代价随词典规模增长，不随文档数线性增长",
                        "能用 term/prefix 就不要用 wildcard：前导 * 让 ES 只能遍历整个词项词典逐个比对")
                .build();
    }

    private Probe probe(String label, Object query, String note) {
        JsonNode d = es.post("/" + SEARCH + "/_search",
                Json.map("size", 0, "track_total_hits", true, "query", query));
        return new Probe(label,
                d.path("hits").path("total").path("value").asLong(0),
                d.path("took").asInt(0), note);
    }

    private record Probe(String label, long total, int took, String note) {
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
