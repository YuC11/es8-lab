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
 * m9-5：输入框里的搜索。
 *
 * 三种补全方式解决的是不同问题：
 *   · term / phrase suggester —— 用户打错字，猜他想打什么；
 *   · completion —— 用户还没打完，给出完整候选（前缀必须从开头匹配）；
 *   · search_as_you_type + bool_prefix —— 支持词中间补全。
 */
@Component
public class M95Suggest implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;

    private final EsClient es;
    private final LabData lab;

    public M95Suggest(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-5";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.ensureSearch();

        // @focus-start 两种 suggester：term 猜单个词，phrase 猜整句并保留上下文
        JsonNode term = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "suggest", Json.map("term_sug", Json.map(
                        "text", "蓝牙耳几",
                        "term", Json.map("field", "title", "suggest_mode", "popular", "size", 3)))));
        JsonNode phrase = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "suggest", Json.map("phrase_sug", Json.map(
                        "text", "蓝牙耳几",
                        "phrase", Json.map(
                                "field", "title",
                                "size", 2,
                                "gram_size", 2,
                                "direct_generator", List.of(Json.map(
                                        "field", "title", "suggest_mode", "always")))))));
        // @focus-end

        // completion：FST 前缀补全，只能从候选串开头匹配
        JsonNode compMillet = completion("小米");
        JsonNode compBlue = completion("蓝牙");

        // @focus-start sayt + bool_prefix：词中间也能补全，召回通常比 phrase_prefix 更宽
        JsonNode sayt = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "track_total_hits", true,
                "query", Json.map("multi_match", Json.map(
                        "query", "蓝牙耳",
                        "type", "bool_prefix",
                        "fields", List.of("title_sayt", "title_sayt._2gram", "title_sayt._3gram")))));
        // @focus-end

        JsonNode prefix = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "track_total_hits", true,
                "query", Json.map("match_phrase_prefix", Json.map("title", "蓝牙耳"))));

        // @focus-start 中英对照：同一个「补全」问题，英文靠 english 分析器（词干化），中文靠 ik
        JsonNode en = es.post("/_analyze", Json.map("analyzer", "english", "text", "Bluetooth Earphones"));
        JsonNode enStd = es.post("/_analyze", Json.map("analyzer", "standard", "text", "Bluetooth Earphones"));
        List<String> enTokens = tokens(en);
        List<String> enStdTokens = tokens(enStd);
        // @focus-end

        long saytTotal = sayt.path("hits").path("total").path("value").asLong(0);
        long prefixTotal = prefix.path("hits").path("total").path("value").asLong(0);
        int termOptions = options(term, "term_sug");
        int phraseOptions = options(phrase, "phrase_sug");
        int milletOptions = options(compMillet, "comp");
        int blueOptions = options(compBlue, "comp");

        double completionScore = score(compMillet, "comp");
        int reloadCount = 0;
        JsonNode reload = es.post("/" + SEARCH + "/_reload_search_analyzers", null);
        reloadCount = reload.path("reload_details").size();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("term suggester", "蓝牙耳几", termOptions + " 个候选",
                termOptions == 0 ? "分词后找不到相近词项" : firstOption(term, "term_sug")));
        rows.add(TableData.row("phrase suggester", "蓝牙耳几", phraseOptions + " 个候选",
                phraseOptions == 0 ? "同上的原因" : firstOption(phrase, "phrase_sug")));
        rows.add(TableData.row("completion", "小米", milletOptions + " 个候选", firstOption(compMillet, "comp")));
        rows.add(TableData.row("completion", "蓝牙", blueOptions + " 个候选", "前缀必须从候选串开头算起"));
        rows.add(TableData.row("bool_prefix", "蓝牙耳", fmt(saytTotal) + " 条", "词中间也能补全"));
        rows.add(TableData.row("match_phrase_prefix", "蓝牙耳", fmt(prefixTotal) + " 条", "要求从词头开始"));
        rows.add(TableData.row("english 分析器", "Bluetooth Earphones", String.join(" / ", enTokens),
                "词干化：earphones → earphon"));
        rows.add(TableData.row("standard 分析器", "Bluetooth Earphones", String.join(" / ", enStdTokens),
                "只切词、不还原词干"));
        rows.add(TableData.row("completion 的 _score", String.valueOf(completionScore),
                "固定值，不是相关性分"));

        return LevelResult.builder("三种补全方式实测下来：term / phrase suggester 对中文给出 0 个候选；"
                        + "completion 用「小米」有 " + milletOptions + " 个、用「蓝牙」为 0（只认串首）；"
                        + "bool_prefix 用「蓝牙耳」命中 " + fmt(saytTotal) + " 条，比 phrase_prefix 宽")
                .metrics(
                        new Metric("中英分词对照", String.join("/", enTokens) + " vs " + String.join("/", enStdTokens),
                                "english 还原词干，standard 不还原"),
                        new Metric("completion 的 _score", String.valueOf(completionScore),
                                "固定值（默认 1.0），不参与相关性"),
                        new Metric("_reload 返回的 detail 条数", reloadCount,
                                "本机规则是内联的，reload 只重读文件，规则不会变"),
                        new Metric("term 候选", termOptions, "中文分词后难凑出候选"),
                        new Metric("phrase 候选", phraseOptions, "同上，整句纠错也未命中"),
                        new Metric("completion(小米)", milletOptions, "FST 前缀命中"),
                        new Metric("completion(蓝牙)", blueOptions, "中段词不匹配"),
                        new Metric("bool_prefix", fmt(saytTotal) + " 条", "phrase_prefix " + fmt(prefixTotal) + " 条"))
                .table(TableData.of(List.of("方式", "输入", "结果", "说明"), rows))
                .json(Json.map("term", term, "phrase", phrase, "completion", compMillet,
                        "boolPrefix", Json.map("query", "multi_match/type=bool_prefix", "total", saytTotal),
                        "phrasePrefix", Json.map("query", "match_phrase_prefix/title", "total", prefixTotal),
                        "analyze", Json.map("english", enTokens, "standard", enStdTokens)))
                .notes(
                        "term suggester 靠「词项编辑距离 + 词频」找候选：中文经 ik 分词后词项很短，"
                                + "再加上要求候选词频足够高，实测在这些错字上都给不出候选",
                        "中文纠错的常见做法不是 term suggester：要么维护「错词 → 正确词」词典，要么用拼音/向量召回；"
                                + "suggester 更适合英文单词拼写",
                        "completion 走 FST 前缀查找：_score 是一个固定值（本机 " + completionScore
                                + "，由 weight 决定），不是相关性分，也不参与排序；它只从候选串开头匹配",
                        "search_as_you_type 会额外建 _2gram / _3gram 子字段，索引体积明显变大；只对标题这类短字段开",
                        "suggester 只负责「给候选」，最终搜哪个还是用户点选后发一次正常查询")
                .build();
    }

    private JsonNode completion(String prefix) {
        return es.post("/" + SEARCH + "/_search", Json.map(
                "size", 0,
                "suggest", Json.map("comp", Json.map(
                        "prefix", prefix,
                        "completion", Json.map("field", "title_suggest", "size", 5)))));
    }

    private static List<String> tokens(JsonNode d) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : d.path("tokens")) out.add(t.path("token").asText());
        return out;
    }

    /** completion 候选里第一个选项的 _score（固定值，不是相关性） */
    private static double score(JsonNode d, String name) {
        JsonNode s = d.path("suggest").path(name);
        if (!s.isArray() || s.isEmpty()) return 0;
        JsonNode opts = s.get(0).path("options");
        if (opts.isEmpty()) return 0;
        return opts.get(0).path("_score").asDouble(0);
    }

    private static int options(JsonNode d, String name) {
        JsonNode s = d.path("suggest").path(name);
        if (!s.isArray() || s.isEmpty()) return 0;
        return s.get(0).path("options").size();
    }

    private static String firstOption(JsonNode d, String name) {
        JsonNode s = d.path("suggest").path(name);
        if (!s.isArray() || s.isEmpty()) return "—";
        JsonNode opts = s.get(0).path("options");
        if (opts.isEmpty()) return "—";
        return opts.get(0).path("text").asText("—");
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
