package com.eslab.levels;

import com.eslab.data.DatasetService;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M2-1 同一个中文句子，三种分词器切出什么
 *
 * 分词器决定了倒排索引里到底有哪些词项，也就决定了「用户搜这个词能不能召回」。
 * 中文场景下 standard 是逐字切分，与 ik 的成词切分不是「粗一点/细一点」的差别，而是词项集合完全不同。
 *
 * 本关给三层证据：
 *   ① 逐 token 的 position 与 offset：能看出谁按词、谁按字，以及词与原文的对应位置；
 *   ② 同一查询在三种查询期分析器下的命中与最高分：错配时命中反而更少、分数更低（不是「更多噪音」）；
 *   ③ v2 的真实配置（索引期 ik_max_word / 查询期 ik_smart）—— 分词器是一对参数，不是一个。
 */
@Component
public class M21AnalyzerCompare implements LevelModule {

    private static final String INDEX = DatasetService.V2;
    /** 长句：能看出 ik_max_word 的穷举（机主、长/续航 这类切法） */
    private static final String TEXT = "小米无线蓝牙耳机主动降噪长续航";
    /** 查询串：短词，用来对比三种查询期分析器的召回 */
    private static final String QUERY = "蓝牙耳机";

    private final EsClient es;
    private final DatasetService dataset;

    public M21AnalyzerCompare(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m2-1";
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(INDEX) || dataset.count(INDEX) == 0) {
            throw new IllegalStateException("v2 还没有数据：先完成 m1-2（显式 Mapping 会建出 v2 并灌入数据）");
        }

        // @focus-start 对同一段文本跑三遍 _analyze，只换 analyzer
        List<Token> standard = analyze("standard", TEXT);
        List<Token> ikSmart = analyze("ik_smart", TEXT);
        List<Token> ikMax = analyze("ik_max_word", TEXT);
        // @focus-end

        // 查询期错配的后果：同一个查询串，三种分析器，三组命中与最高分
        long stdHits = hits(QUERY, "standard");
        double stdTop = topScore(QUERY, "standard");
        long smartHits = hits(QUERY, "ik_smart");
        double smartTop = topScore(QUERY, "ik_smart");
        long maxHits = hits(QUERY, "ik_max_word");
        double maxTop = topScore(QUERY, "ik_max_word");

        // v2 的 title 到底配了哪两个分析器
        JsonNode titleDef = es.get("/" + INDEX + "/_mapping").path(INDEX)
                .path("mappings").path("properties").path("title");
        String indexAnalyzer = titleDef.path("analyzer").asText("standard(默认)");
        String searchAnalyzer = titleDef.path("search_analyzer").asText(indexAnalyzer);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("standard（默认）", standard.size(), render(standard),
                stdHits + " 条 / 最高 " + fmt(stdTop), "逐字切分：索引里根本没有「蓝牙」这个整词"));
        rows.add(TableData.row("ik_smart", ikSmart.size(), render(ikSmart),
                smartHits + " 条 / 最高 " + fmt(smartTop), "粗粒度：最长匹配优先，词项少、体积小"));
        rows.add(TableData.row("ik_max_word", ikMax.size(), render(ikMax),
                maxHits + " 条 / 最高 " + fmt(maxTop), "细粒度：穷举所有成词组合，召回高、词项多"));

        return LevelResult.builder("同一句「" + TEXT + "」：standard 切出 " + standard.size()
                        + " 个单字、ik_smart " + ikSmart.size() + " 个词、ik_max_word " + ikMax.size()
                        + " 个词；用 standard 查「" + QUERY + "」只命中 " + stdHits + " 条、最高分 " + fmt(stdTop)
                        + "，而 ik_smart 命中 " + smartHits + " 条、最高分 " + fmt(smartTop)
                        + " —— 分词器错配不是「多返回噪音」，而是「召回更少、分数更低」")
                .metrics(
                        new Metric("standard 词项数", standard.size(), "逐字"),
                        new Metric("ik_smart 词项数", ikSmart.size(), "粗粒度成词"),
                        new Metric("ik_max_word 词项数", ikMax.size(), "细粒度穷举"),
                        new Metric("同一查询命中", "standard " + stdHits + " / ik " + smartHits,
                                "查询串「" + QUERY + "」"),
                        new Metric("最高分", "standard " + fmt(stdTop) + " / ik " + fmt(smartTop),
                                "分数低说明匹配的词项更少"),
                        new Metric("v2 的 title 配置", indexAnalyzer + " → " + searchAnalyzer,
                                "索引期与查询期是一对参数"))
                .table(new TableData(List.of("分词器", "词项数", "切分结果（词项@位置）", "同一查询", "特点"), rows))
                .json(Json.map(
                        "text", TEXT,
                        "query", QUERY,
                        "standard", plain(standard),
                        "ikSmart", plain(ikSmart),
                        "ikMax", plain(ikMax),
                        "recall", Json.map("standard", stdHits, "ik_smart", smartHits, "ik_max_word", maxHits),
                        "mapping", Json.map("analyzer", indexAnalyzer, "search_analyzer", searchAnalyzer)))
                .notes(
                        "长度不是重点，词项集合才是：standard 对中文按单字切，ik 按词典成词。"
                                + "所以 standard 查「" + QUERY + "」得到的是「蓝」「牙」「耳」「机」四个单字词项，"
                                + "而索引里存的是「蓝牙」「耳机」—— 两边对不上，命中 "
                                + stdHits + " 条，只有碰巧含单字词项的文档才被召回。",
                        "ik_max_word 会把「主动降噪」切出 主动/降噪，还会切出 机主 这种跨词边界的组合；"
                                + "ik_smart 只给最长的那个。前者词项多、召回高、索引大，后者相反。",
                        "v2 的 title 的实际配置是索引期 " + indexAnalyzer + "、查询期 " + searchAnalyzer
                                + "：索引期尽量多切（提高召回），查询期用粗粒度（避免查询串被切碎、提高精度）。"
                                + "这是中文全文检索最常见的组合。",
                        "分词器缺失时的报错是 `Failed to find analyzer [ik_smart]`（或 `analyzer [ik_max_word] not found`）："
                                + "说明 analysis-ik 插件没装，`GET /_cat/plugins` 应当能列出来。"
                                + "本关在缺失时会直接给出这条前置提示，而不是抛一个看不懂的 400。",
                        "英文不需要 IK：standard 对英文按空格与标点切词并小写化。中英混排的字段才需要 IK + 自定义链条。")
                .build();
    }

    /** 请求一次 _analyze，返回 token + position + offset；插件缺失时给可读的前置错误 */
    private List<Token> analyze(String analyzer, String text) {
        try {
            JsonNode res = es.post("/" + INDEX + "/_analyze",
                    Json.map("analyzer", analyzer, "text", text));
            List<Token> out = new ArrayList<>();
            for (JsonNode t : res.path("tokens")) {
                out.add(new Token(t.path("token").asText(), t.path("position").asInt(),
                        t.path("start_offset").asInt(), t.path("end_offset").asInt()));
            }
            return out;
        } catch (EsException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("not found") || msg.contains("Failed to find analyzer")) {
                throw new IllegalStateException("分词器 " + analyzer + " 不存在：analysis-ik 插件未安装。"
                        + "先执行 GET /_cat/plugins 确认 component=analysis-ik，再重启章节。原始错误：" + msg);
            }
            throw e;
        }
    }

    private long hits(String query, String analyzer) {
        return es.post("/" + INDEX + "/_search", Json.map(
                "size", 0, "track_total_hits", true,
                "query", Json.map("match", Json.map("title",
                        Json.map("query", query, "analyzer", analyzer)))))
                .path("hits").path("total").path("value").asLong();
    }

    private double topScore(String query, String analyzer) {
        JsonNode hits = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1,
                "query", Json.map("match", Json.map("title",
                        Json.map("query", query, "analyzer", analyzer)))))
                .path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return 0;
        return hits.get(0).path("_score").asDouble(0);
    }

    private record Token(String token, int position, int start, int end) {
    }

    private static String render(List<Token> tokens) {
        List<String> parts = new ArrayList<>();
        for (Token t : tokens) parts.add(t.token());
        return String.join(" | ", parts);
    }

    private static List<String> plain(List<Token> tokens) {
        List<String> out = new ArrayList<>();
        for (Token t : tokens) out.add(t.token() + "@" + t.position() + "[" + t.start() + "," + t.end() + ")");
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
