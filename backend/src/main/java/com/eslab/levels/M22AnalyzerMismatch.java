package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.Generator;
import com.eslab.seed.ProductDoc;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2-2 索引期与查询期分词器不一致会怎样
 *
 * 倒排索引里存的是「索引期分词结果」，查询时如果用了另一套分词器，两边词项对不上，
 * 就会出现「明明有这条数据却搜不到」。
 *
 * 本关用两个只差分析器的索引做受控对照（文档、查询、分片数、刷新策略完全一致）：
 *   A 正确：索引期 ik_max_word + 查询期 ik_smart —— 两边都是成词单元，能命中；
 *   B 错配：索引期 standard（单字）+ 查询期 ik_smart（词）—— 词项完全不重合，命中 0。
 * 然后对 B 做两件事，把「哪些能原地改、哪些必须重建」变成两条响应：
 *   ① 原地把 search_analyzer 改成 standard → 200 acknowledged，查询立刻能命中（查询侧可改）；
 *   ② 想把索引期 analyzer 改成 ik_max_word → 400，Cannot update parameter [analyzer]（倒排已写死，只能重建）。
 */
@Component
public class M22AnalyzerMismatch implements LevelModule {

    private static final String OK = LabIndexes.ANALYZER_OK;
    private static final String BAD = LabIndexes.ANALYZER_BAD;
    private static final String QUERY = "蓝牙耳机";
    private static final int DOCS = 200;

    private final EsClient es;
    private final LabData lab;

    public M22AnalyzerMismatch(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m2-2";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 两个索引：文档与查询完全相同，只有 analyzer / search_analyzer 这一组参数不同
        lab.rebuild(OK, idx -> fill(idx));
        lab.rebuild(BAD, idx -> fill(idx));
        // @focus-end

        long okHits;
        long badHits;
        long fixedHits;
        String ackSearchAnalyzer;
        String conflictError = "";
        String conflictReason = "";
        String okIndexTokens;
        String badIndexTokens;
        String searchTokens;
        double okTop;
        double badTop;
        try {
            okHits = hits(OK);
            okTop = topScore(OK);
            badHits = hits(BAD);
            badTop = topScore(BAD);

            // 两边索引期的实际词项：A 是 ik 的成词结果，B 是标准分词器的单字
            okIndexTokens = indexTokens(OK);
            badIndexTokens = indexTokens(BAD);
            searchTokens = searchTokens();

            // ① 查询期分析器可以原地改：改完再查一次
            JsonNode ack = es.put("/" + BAD + "/_mapping", Json.map("properties", Json.map(
                    "title", Json.map("type", "text",
                            "analyzer", "standard", "search_analyzer", "standard"))));
            ackSearchAnalyzer = ack.path("acknowledged").asBoolean(true) ? "acknowledged" : "未确认";
            fixedHits = hits(BAD);

            // ② 索引期分析器改不动：倒排已经按单字写好了
            try {
                es.put("/" + BAD + "/_mapping", Json.map("properties", Json.map(
                        "title", Json.map("type", "text",
                                "analyzer", "ik_max_word", "search_analyzer", "standard"))));
                conflictError = "居然成功了（异常）";
            } catch (EsException e) {
                conflictError = e.status() + " " + errorType(e);
                conflictReason = firstReason(e);
            }
        } finally {
            // 两个对照索引都是一次性的：异常路径也要删
            if (lab.exists(OK)) lab.delete(OK);
            if (lab.exists(BAD)) lab.delete(BAD);
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("A " + OK, "ik_max_word", "ik_smart", okIndexTokens,
                okHits + " 条 / 最高 " + fmt(okTop), "正确配置：两边都是成词单元"));
        rows.add(TableData.row("B " + BAD, "standard", "ik_smart", badIndexTokens,
                badHits + " 条 / 最高 " + fmt(badTop), "索引按字、查询按词：词项完全不重合"));
        rows.add(TableData.row("B（原地改查询期）", "standard", "standard", badIndexTokens,
                fixedHits + " 条", "查到了，但语义已降级为单字匹配 —— " + ackSearchAnalyzer));
        rows.add(TableData.row("B（想改索引期）", "→ ik_max_word", "standard", "—",
                conflictError.isEmpty() ? "未触发" : "被拒绝",
                conflictReason.isEmpty() ? "" : clip(conflictReason)));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("query", QUERY);
        json.put("searchTokens", searchTokens);
        json.put("okIndexTokens", okIndexTokens);
        json.put("badIndexTokens", badIndexTokens);
        json.put("hits", Json.map("ok", okHits, "bad", badHits, "badAfterSearchAnalyzerFix", fixedHits));
        json.put("searchAnalyzerUpdate", ackSearchAnalyzer);
        json.put("indexAnalyzerConflict", Json.map("status", conflictError, "reason", conflictReason));

        return LevelResult.builder("同一批 " + DOCS + " 条文档、同一个查询「" + QUERY + "」："
                        + "索引期与查询期都用成词分析器时命中 " + okHits + " 条，"
                        + "索引期换成 standard 后命中 " + badHits + " 条 —— "
                        + "两边词项对不上，数据在库里也搜不出来")
                .metrics(
                        new Metric("A 正确配置命中", okHits + " 条", "索引 ik_max_word / 查询 ik_smart"),
                        new Metric("B 错配命中", badHits + " 条", "索引 standard / 查询 ik_smart"),
                        new Metric("B 原地改查询期后", fixedHits + " 条",
                                "PUT _mapping 改 search_analyzer：" + ackSearchAnalyzer),
                        new Metric("改索引期 analyzer", conflictError.isEmpty() ? "未触发" : "被拒绝",
                                "倒排已按单字写好，只能重建"),
                        new Metric("索引期词项对比", "A " + tokenCount(okIndexTokens)
                                        + " 个词 / B " + tokenCount(badIndexTokens) + " 个单字",
                                "查询词项：" + searchTokens))
                .table(new TableData(List.of("索引", "索引期 analyzer", "查询期 analyzer",
                        "索引里的词项", "查询结果", "结论 / 报错"), rows))
                .json(json)
                .notes(
                        "「搜不到」的第一排查点不是查询语句，而是两边的词项对不对得上："
                                + "索引里是 " + badIndexTokens + "，查询串却切成 " + searchTokens + "，交集为空就必然是 0 条。",
                        conflictReason.isEmpty()
                                ? "本次没有触发索引期 analyzer 的拒绝（异常）"
                                : "索引期 analyzer 改不了，报错原文：" + clip(conflictReason)
                                + "。原因是倒排里已经写进了单字词项，改参数不会重写历史数据 —— "
                                + "只能新建索引 + _reindex 把数据搬过去。",
                        "查询期 search_analyzer 是另一回事：它能被原地更新（本次返回 " + ackSearchAnalyzer
                                + "），改完新查询立刻按新分析器切词。但它只能修「查询串怎么切」，"
                                + "修不了「索引里存了什么」—— 所以上一条的 400 依然成立。",
                        "这条边界在运维上很实用：怀疑查询侧切错了，可以先临时覆盖（查询里带 analyzer 参数，或用 "
                                + "search_analyzer 原地改）验证；确认是索引期的问题，才动 reindex 这种重活。",
                        "本关两个索引都已删除；v2 的 mapping 一个字节都没改。")
                .build();
    }

    /** 200 条精简商品：只写 sku 与 title，两个索引写的是同一批数据 */
    private int fill(String index) {
        return lab.bulkGenerate(index, 0, DOCS, i -> {
            ProductDoc d = Generator.makeDoc(i, 42);
            return Json.map("sku", d.sku(), "title", d.title());
        });
    }

    private long hits(String index) {
        return es.post("/" + index + "/_search", Json.map(
                "size", 0, "track_total_hits", true,
                "query", Json.map("match", Json.map("title", QUERY))))
                .path("hits").path("total").path("value").asLong();
    }

    private double topScore(String index) {
        JsonNode hits = es.post("/" + index + "/_search", Json.map(
                "size", 1, "query", Json.map("match", Json.map("title", QUERY))))
                .path("hits").path("hits");
        if (!hits.isArray() || hits.isEmpty()) return 0;
        return hits.get(0).path("_score").asDouble(0);
    }

    /** 用 field 参数取「索引期」分析器切出来的词项 */
    private String indexTokens(String index) {
        try {
            JsonNode res = es.post("/" + index + "/_analyze",
                    Json.map("field", "title", "text", QUERY));
            List<String> tokens = new ArrayList<>();
            for (JsonNode t : res.path("tokens")) tokens.add(t.path("token").asText());
            return String.join("/", tokens);
        } catch (EsException e) {
            return "取不到：" + e.status();
        }
    }

    private String searchTokens() {
        JsonNode res = es.post("/" + BAD + "/_analyze",
                Json.map("analyzer", "ik_smart", "text", QUERY));
        List<String> tokens = new ArrayList<>();
        for (JsonNode t : res.path("tokens")) tokens.add(t.path("token").asText());
        return String.join("/", tokens);
    }

    private static int tokenCount(String s) {
        return s.isEmpty() ? 0 : s.split("/").length;
    }

    private static String errorType(EsException e) {
        JsonNode payload = e.payload();
        if (payload != null && payload.has("error")) {
            return payload.path("error").path("type").asText("error");
        }
        return "error";
    }

    private static String firstReason(EsException e) {
        JsonNode payload = e.payload();
        if (payload != null && payload.path("error").path("root_cause").isArray()
                && !payload.path("error").path("root_cause").isEmpty()) {
            return payload.path("error").path("root_cause").path(0).path("reason").asText("");
        }
        return e.getMessage() == null ? "" : e.getMessage();
    }

    private static String clip(String s) {
        return s.length() <= 110 ? s : s.substring(0, 110) + "…";
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
