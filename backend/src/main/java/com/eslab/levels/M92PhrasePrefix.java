package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m9-2：短语、顺序与前缀。
 *
 * match 是「词都出现就行」，match_phrase 是「词要按顺序相邻」，
 * slop 是「允许隔几个词」—— 三者召回依次收紧，语义依次精确。
 *
 * 本关的统一输入是「蓝牙耳机」：match / match_phrase 用它，slop 那一对用它的逆序「耳机 蓝牙」，
 * prefix 与 wildcard 用它作为前缀/子串。只有输入统一，命中数才可比。
 * 耗时一律 5 轮中位（took），并且给 wildcard 加了 size:0 与说明 —— 它的代价在词典规模，不在返回条数。
 */
@Component
public class M92PhrasePrefix implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;
    private static final String TERM = "蓝牙耳机";
    private static final int ROUNDS = 5;

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

        // @focus-start 同一个词「蓝牙耳机」：词出现即可 / 必须相邻 / 词序颠倒 / 放宽间隔
        Probe m = probe("match", Json.map("match", Json.map("title", TERM)),
                "词出现即可，不考虑顺序");
        Probe p = probe("match_phrase", Json.map("match_phrase", Json.map("title", TERM)),
                "必须相邻且顺序一致");
        Probe s0 = probe("phrase(耳机 蓝牙) slop 0",
                Json.map("match_phrase", Json.map("title", Json.map("query", "耳机 蓝牙", "slop", 0))),
                "逆序 + slop 0：查不到");
        Probe s3 = probe("phrase(耳机 蓝牙) slop 3",
                Json.map("match_phrase", Json.map("title", Json.map("query", "耳机 蓝牙", "slop", 3))),
                "允许中间隔 3 个词，逆序也能命中");
        // @focus-end

        // @focus-start 前缀与通配：同一个串，一个能剪枝、一个要扫全词典
        Probe pre = probe("prefix", Json.map("prefix", Json.map("title.keyword", TERM)),
                "「以它开头」才命中：本机没有标题以「蓝牙耳机」开头");
        Probe wild = probe("wildcard *蓝牙耳机*", Json.map("wildcard", Json.map("title.keyword", "*" + TERM + "*")),
                "前后都通配，只能逐词项比对");
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        for (Probe x : List.of(m, p, s0, s3, pre, wild)) {
            rows.add(TableData.row(x.label, fmt(x.total), num(x.median) + " ms",
                    joinRuns(x.samples), x.note));
        }

        Map<String, Object> json = new LinkedHashMap<>();
        for (Probe x : List.of(m, p, s0, s3, pre, wild)) {
            json.put(x.label, Json.map("query", x.query, "total", x.total, "samples", x.samples));
        }

        return LevelResult.builder("同一个串「" + TERM + "」：match 命中 " + fmt(m.total)
                        + "，「必须相邻」后剩 " + fmt(p.total) + "；把词序颠倒，slop 0 是 " + fmt(s0.total)
                        + "，放宽到 slop 3 回到 " + fmt(s3.total)
                        + "；prefix 是「以它开头」，本机 " + fmt(pre.total) + " 条，"
                        + "前后通配（包含）则是 " + fmt(wild.total) + " 条 —— 语义不同，别把 prefix 当 contains")
                .metrics(
                        new Metric("match", fmt(m.total) + " 条", "词出现即可"),
                        new Metric("match_phrase", fmt(p.total) + " 条", "必须相邻同序，slop 默认 0"),
                        new Metric("逆序 slop 0 → 3", fmt(s0.total) + " → " + fmt(s3.total),
                                "slop 是「允许移动几次位置」，不是「隔几个字」"),
                        new Metric("prefix", fmt(pre.total) + " 条 · " + num(pre.median) + " ms",
                                "5 轮 took 中位"),
                        new Metric("wildcard", fmt(wild.total) + " 条 · " + num(wild.median) + " ms",
                                "前后通配，无法剪枝"),
                        new Metric("match_phrase / match", m.total == 0 ? "—"
                                        : String.format(Locale.ROOT, "%.1f%%", p.total * 100.0 / m.total),
                                "相邻约束把召回压到多少"))
                .table(TableData.of(List.of("查询", "命中", "took（中位）", "5 轮样本", "说明"), rows))
                .json(json)
                .notes(
                        "match_phrase 要求词项位置连续；slop 是「允许移动几次位置」，不是「隔几个字符」—— 逆序查询要靠 slop 才可能命中",
                        "prefix 与 wildcard 的语义不同：prefix 要求「以该串开头」（本机 0 条，因为标题都以品牌开头），"
                                + "前导 * 的 wildcard 是「包含」（本机 " + fmt(wild.total) + " 条）—— 后者无法用前缀区间剪枝，只能遍历词典",
                        "本机 2 万条时两种写法的 took 可能都只有 1–2ms，差异落在噪声里 —— 代价随词典规模增长，不随文档数线性增长；所以表格给的是中位与样本，不是「快 N 倍」",
                        "wildcard 一律配 size:0 / 限量返回：它不参与提前终止，返回条数越多越慢")
                .build();
    }

    private Probe probe(String label, Object query, String note) throws Exception {
        Timing.Sample sample = Timing.sample(ROUNDS, 1, round -> {
            JsonNode d = es.post("/" + SEARCH + "/_search",
                    Json.map("size", 0, "track_total_hits", true, "query", query));
            return d.path("took").asDouble();
        });
        JsonNode d = es.post("/" + SEARCH + "/_search",
                Json.map("size", 0, "track_total_hits", true, "query", query));
        return new Probe(label, d.path("hits").path("total").path("value").asLong(0),
                sample.median(), sample.runs(), query, note);
    }

    private static String joinRuns(List<Double> runs) {
        List<String> parts = new ArrayList<>(runs.size());
        for (double v : runs) parts.add(num(v));
        return String.join(" / ", parts);
    }

    private static String num(double v) {
        if (!Double.isInfinite(v) && !Double.isNaN(v) && v == Math.rint(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    private record Probe(String label, long total, double median, List<Double> samples,
                         Object query, String note) {
    }
}
