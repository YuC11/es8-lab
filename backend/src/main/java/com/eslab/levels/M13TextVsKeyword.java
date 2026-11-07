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
 * M1-3 text 与 keyword 的本质区别
 *
 * 同一个字段值，text 存的是「分词后的词项」，keyword 存的是「原始字符串」。
 * 这决定了：term/match 能不能命中、能不能排序、能不能聚合。
 *
 * 本关固定一个关键词「蓝牙耳机」，在同一个字段的两种视角上各跑一遍四种用法：
 * 检索（match / term）、精确整串（term 整串）、排序（sort）、聚合（terms）。
 * 失败的那几次不是「配置错了」，而是这类字段本来就不支持这种操作 —— 报错原文就是证据。
 */
@Component
public class M13TextVsKeyword implements LevelModule {

    private static final String INDEX = DatasetService.V2;
    /** 固定关键词：数据里确实存在（PRODUCT_KINDS 里有两个含它的品类） */
    private static final String KEYWORD = "蓝牙耳机";
    private static final String TAG = "新品";

    private final EsClient es;
    private final DatasetService dataset;

    public M13TextVsKeyword(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m1-3";
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(INDEX) || dataset.count(INDEX) == 0) {
            throw new IllegalStateException("v2 还没有数据：先完成 m1-2（显式 Mapping 会建出 v2 并灌入数据）");
        }
        // 先取一条真实文档，拿到完整标题用于「精确整串」对照
        JsonNode one = es.post("/" + INDEX + "/_search",
                Json.map("size", 1, "sort", Json.list(Json.map("sku", "asc")),
                        "query", Json.map("match", Json.map("title", KEYWORD))));
        String fullTitle = one.path("hits").path("hits").path(0).path("_source").path("title").asText("");

        // @focus-start 同一份数据上的四种查法：text 字段 vs keyword 子字段
        long termOnText = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("term", Json.map("title", KEYWORD)))));
        long matchOnText = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("match", Json.map("title", KEYWORD)))));
        long termOnKeywordFull = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("term", Json.map("title.keyword", fullTitle)))));
        long termOnKeywordPartial = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("term", Json.map("title.keyword", KEYWORD)))));
        long termOnTags = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("term", Json.map("tags", TAG)))));
        // @focus-end

        // 排序边界：text 无 doc_values，keyword 有
        String sortTextError = "";
        String sortTextReason = "";
        try {
            es.post("/" + INDEX + "/_search", Json.map("size", 1, "sort", Json.list(Json.map("title", "asc"))));
        } catch (EsException e) {
            sortTextError = e.status() + " " + errorType(e);
            sortTextReason = firstReason(e);
        }
        int sortKwHits = 0;
        String sortKwFirst = "";
        try {
            JsonNode ok = es.post("/" + INDEX + "/_search",
                    Json.map("size", 1, "sort", Json.list(Json.map("title.keyword", "asc"))));
            sortKwHits = ok.path("hits").path("hits").size();
            sortKwFirst = ok.path("hits").path("hits").path(0).path("_source").path("title").asText("");
        } catch (EsException e) {
            sortKwFirst = "也失败了：" + e.getMessage();
        }

        // 聚合边界：text 需要 fielddata（默认关闭），keyword 直接可聚合
        String aggTextError = "";
        String aggTextReason = "";
        try {
            es.post("/" + INDEX + "/_search", Json.map("size", 0,
                    "aggs", Json.map("t", Json.map("terms", Json.map("field", "title")))));
        } catch (EsException e) {
            aggTextError = e.status() + " " + errorType(e);
            aggTextReason = firstReason(e);
        }
        List<String> buckets = new ArrayList<>();
        try {
            JsonNode agg = es.post("/" + INDEX + "/_search", Json.map("size", 0,
                    "aggs", Json.map("t", Json.map("terms",
                            Json.map("field", "title.keyword", "size", 3)))));
            for (JsonNode b : agg.path("aggregations").path("t").path("buckets")) {
                buckets.add(clip(b.path("key").asText(), 18) + "×" + b.path("doc_count").asLong());
            }
        } catch (EsException e) {
            buckets.add("失败：" + e.getMessage());
        }

        JsonNode analyzed = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "ik_smart", "text", KEYWORD));
        List<String> tokens = new ArrayList<>();
        for (JsonNode t : analyzed.path("tokens")) tokens.add(t.path("token").asText());

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("match title=\"" + KEYWORD + "\"", "text", matchOnText + " 条",
                "查询串先经 ik_smart 分析成 " + tokens.size() + " 个词项（" + String.join(" / ", tokens)
                        + "），再按词项匹配"));
        rows.add(TableData.row("term title=\"" + KEYWORD + "\"", "text", termOnText + " 条",
                "term 不做分析，直接查「" + KEYWORD + "」这个整词项 —— 词典里没有"));
        rows.add(TableData.row("term title.keyword=整串标题", "keyword", termOnKeywordFull + " 条",
                "keyword 存原始串，必须整串完全相等"));
        rows.add(TableData.row("term title.keyword=\"" + KEYWORD + "\"", "keyword", termOnKeywordPartial + " 条",
                "子串不等于整串 —— keyword 不做包含匹配"));
        rows.add(TableData.row("term tags=\"" + TAG + "\"", "keyword", termOnTags + " 条",
                "tags 是 keyword 数组，每个标签就是一个独立词项"));
        rows.add(TableData.row("sort title", "text", sortTextError.isEmpty() ? "成功" : "被拒绝",
                sortTextReason.isEmpty() ? "" : "无 doc_values：" + clip(sortTextReason, 70)));
        rows.add(TableData.row("sort title.keyword", "keyword",
                sortKwFirst.isEmpty() ? "失败" : "成功", "首条：" + clip(sortKwFirst, 20)));
        rows.add(TableData.row("terms agg title", "text", aggTextError.isEmpty() ? "成功" : "被拒绝",
                aggTextReason.isEmpty() ? "" : "要开 fielddata：" + clip(aggTextReason, 70)));
        rows.add(TableData.row("terms agg title.keyword", "keyword",
                buckets.isEmpty() ? "失败" : String.join("｜", buckets), "top-3 桶"));

        return LevelResult.builder("term 是「不分析直接查词项」，match 是「先分析再查词项」——"
                        + "对 text 用 term，等于绕过分词器去查一个不存在的词项；排序与聚合则卡在 doc_values："
                        + "text 没有，keyword 有")
                .metrics(
                        new Metric("match 查 text", matchOnText + " 条", "分词后匹配，能召回"),
                        new Metric("term 查 text", termOnText + " 条", "整词项不存在，必然 0"),
                        new Metric("term 查 keyword", termOnKeywordFull + " 条", "整串相等才命中"),
                        new Metric("term 查 tags", termOnTags + " 条", "keyword 数组按下标词项匹配"),
                        new Metric("text 排序 / 聚合", (sortTextError.isEmpty() ? "成功" : "排序拒绝")
                                + " / " + (aggTextError.isEmpty() ? "成功" : "聚合拒绝"),
                                "fielddata 默认关闭"),
                        new Metric("ik_smart 词项", tokens.size() + " 个", String.join(" / ", tokens)))
                .table(new TableData(List.of("用法", "字段侧", "命中数 / 结果", "原因"), rows))
                .json(Json.map(
                        "fullTitle", fullTitle,
                        "ikSmartTokens", tokens,
                        "sortTextError", Json.map("status", sortTextError, "reason", sortTextReason),
                        "aggTextError", Json.map("status", aggTextError, "reason", aggTextReason),
                        "buckets", buckets))
                .notes(
                        "判断该用哪种：要「搜得到」用 text（分词、算相关性），要「精确等于/排序/聚合」用 keyword。",
                        "生产上常见做法是 text + keyword 子字段双写（本项目 title 就是这么建的），代价是多一份索引空间。",
                        "ik_smart 把「" + KEYWORD + "」切成 " + tokens.size() + " 个词项："
                                + String.join(" | ", tokens)
                                + "。换成 ik_max_word 会切得更细，召回更多、精度更低、词项更多。",
                        sortTextReason.isEmpty()
                                ? "本次 text 排序没有报错（异常，请检查 mapping 是否被改过）"
                                : "text 排序失败是结构性原因，不是配置问题：" + clip(sortTextReason, 120)
                                + "。开 fielddata: true 能「修好」，但那会把整个正排搬进堆内存，是 OOM 的经典来源。",
                        aggTextReason.isEmpty()
                                ? "本次 text 聚合没有报错（异常）"
                                : "text 聚合失败同理：" + clip(aggTextReason, 120) + "。",
                        "keyword 也有边界：term 查 keyword 只认整串，「" + KEYWORD + "」是「" + fullTitle
                                + "」的子串，所以查到 " + termOnKeywordPartial + " 条。要子串匹配得用 wildcard 或 ngram，"
                                + "代价是查询成本。")
                .build();
    }

    private static long hits(JsonNode res) {
        return res.path("hits").path("total").path("value").asLong();
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

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
