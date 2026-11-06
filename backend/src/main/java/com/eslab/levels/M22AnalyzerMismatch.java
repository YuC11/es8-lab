package com.eslab.levels;

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
import java.util.Map;

/**
 * M2-2 索引期与查询期分词器不一致会怎样
 *
 * 倒排索引里存的是"索引期分词结果"，查询时如果用了另一套分词器，
 * 两边词项对不上，就会出现"明明有这条数据却搜不到"或"搜出一堆不相关"。
 */
@Component
public class M22AnalyzerMismatch implements LevelModule {

    private static final String INDEX = "eslab-products-v2";
    private static final String Q = "蓝牙耳机";

    private final EsClient es;

    public M22AnalyzerMismatch(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m2-2";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 同一查询，只改查询期 analyzer：默认(ik_smart) vs standard
        JsonNode normal = es.post("/" + INDEX + "/_search", body(null));
        JsonNode wrong = es.post("/" + INDEX + "/_search", body("standard"));
        // @focus-end

        long normalHits = normal.path("hits").path("total").path("value").asLong();
        long wrongHits = wrong.path("hits").path("total").path("value").asLong();

        JsonNode normalTokensRes = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "ik_smart", "text", Q));
        JsonNode wrongTokensRes = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "standard", "text", Q));
        List<String> normalTokens = tokens(normalTokensRes);
        List<String> wrongTokens = tokens(wrongTokensRes);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("默认 search_analyzer（ik_smart）", normalHits, top(normal)));
        rows.add(TableData.row("强制 analyzer=standard", wrongHits, top(wrong)));

        return LevelResult.builder("查询串用错分词器后，命中数从 " + normalHits + " 变成 " + wrongHits
                        + "——这是\"搜不到\"和\"搜出一堆垃圾\"同时出现的典型成因")
                .metrics(
                        new Metric("正常命中（ik_smart）", normalHits, null),
                        new Metric("错配命中（standard）", wrongHits, null),
                        new Metric("词项数变化", normalTokens.size() + " → " + wrongTokens.size(), null),
                        new Metric("最高分", firstScore(normal) + " → " + firstScore(wrong), null))
                .table(new TableData(List.of("查询方式", "命中数", "Top 结果（含相关性分数）"), rows))
                .json(Json.map(
                        "正常查询词项", normalTokens,
                        "错配查询词项", wrongTokens))
                .notes(
                        "索引期 title 用 ik_max_word，查询期默认用 search_analyzer=ik_smart，词项能对齐（各自都是成词单元）。",
                        "强行指定 standard 后，查询串被切成单字 " + String.join("/", wrongTokens)
                                + "，而索引里存的是\"蓝牙\"\"耳机\"这类词项，只能靠单字偶合命中，分数自然低且噪音大。",
                        "排查口诀：搜不到先看 `_analyze` 两边的词项是否一致，再看 mapping 里 analyzer 与 search_analyzer 是否成对。",
                        "这一关用的是真实 20 万文档索引，命中数可直接作为你后续调分词器的基线。")
                .build();
    }

    private static Map<String, Object> body(String analyzer) {
        Object title = analyzer == null ? Q : Json.map("query", Q, "analyzer", analyzer);
        return Json.map(
                "size", 3,
                "track_total_hits", true,
                "_source", Json.list("title", "price"),
                "query", Json.map("match", Json.map("title", title)));
    }

    private static List<String> tokens(JsonNode res) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : res.path("tokens")) {
            out.add(t.path("token").asText());
        }
        return out;
    }

    private static String top(JsonNode r) {
        List<String> parts = new ArrayList<>();
        for (JsonNode h : r.path("hits").path("hits")) {
            parts.add(h.path("_source").path("title").asText()
                    + "（score " + String.format("%.2f", h.path("_score").asDouble()) + "）");
        }
        return parts.isEmpty() ? "无" : String.join("；", parts);
    }

    private static String firstScore(JsonNode r) {
        JsonNode hits = r.path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return "—";
        JsonNode score = hits.get(0).path("_score");
        if (score.isMissingNode() || score.isNull()) return "—";
        return String.format("%.2f", score.asDouble());
    }
}
