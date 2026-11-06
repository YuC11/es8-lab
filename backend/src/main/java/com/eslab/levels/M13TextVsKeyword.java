package com.eslab.levels;

import com.eslab.data.DatasetService;
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
 * M1-3 text 与 keyword 的本质区别
 *
 * 同一个字段值，text 存的是「分词后的词项」，keyword 存的是「原始字符串」。
 * 这决定了：term/match 能不能命中、能不能排序、能不能聚合。
 */
@Component
public class M13TextVsKeyword implements LevelModule {

    private static final String INDEX = "eslab-products-v2";

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
        // 先取一条真实文档，拿到完整标题用于「精确匹配」对照
        JsonNode one = es.post("/" + INDEX + "/_search",
                Json.map("size", 1, "query", Json.map("match", Json.map("title", "蓝牙耳机"))));
        JsonNode doc = one.path("hits").path("hits").path(0).path("_source");
        String fullTitle = doc.path("title").asText("");

        // @focus-start 同一份数据上的四种查法：text 字段 vs keyword 子字段
        long termOnText = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "query", Json.map("term", Json.map("title", "蓝牙耳机")))));
        long matchOnText = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("match", Json.map("title", "蓝牙耳机")))));
        long termOnKeyword = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "query", Json.map("term", Json.map("title.keyword", fullTitle)))));
        long termOnTags = hits(es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true,
                        "query", Json.map("term", Json.map("tags", "新品")))));
        // @focus-end

        JsonNode analyzed = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "ik_smart", "text", "蓝牙耳机"));
        List<String> tokens = new ArrayList<>();
        for (JsonNode t : analyzed.path("tokens")) tokens.add(t.path("token").asText());

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("term title=\"蓝牙耳机\"", "text", termOnText,
                "词项词典里存的是 " + String.join(" / ", tokens) + "，没有「蓝牙耳机」这一整个词项"));
        rows.add(TableData.row("match title=\"蓝牙耳机\"", "text", matchOnText,
                "查询串先经 ik_smart 分析成同样的词项，才能匹配"));
        rows.add(TableData.row("term title.keyword=整串标题", "keyword", termOnKeyword,
                "keyword 存原始串，必须整串完全相等"));
        rows.add(TableData.row("term tags=\"新品\"", "keyword", termOnTags,
                "tags 是 keyword 数组，每个标签就是一个独立词项"));

        return LevelResult.builder("term 是「不分析直接查词项」，match 是「先分析再查词项」——"
                        + "对 text 字段用 term，等于绕过分词器直接查一个不存在的词项")
                .metrics(
                        new Metric("term 查 text", termOnText + " 条", "0 条是正确行为，不是 bug"),
                        new Metric("match 查 text", matchOnText + " 条", null),
                        new Metric("term 查 keyword", termOnKeyword + " 条", "精确到整串"),
                        new Metric("term 查 tags(keyword)", termOnTags + " 条", null))
                .table(new TableData(List.of("查询", "字段类型", "命中", "原因"), rows))
                .json(Map.of("fullTitle", fullTitle, "ikSmartTokens", tokens))
                .notes(
                        "判断该用哪种：要「搜得到」用 text（分词、算相关性），要「精确等于/排序/聚合」用 keyword。",
                        "生产上常见做法是 text + keyword 子字段双写（本项目 title 就是这么建的），代价是多一份索引空间。",
                        "ik_smart 把「蓝牙耳机」切成 " + tokens.size() + " 个词项："
                                + String.join(" | ", tokens)
                                + "。你可以用底部控制台换成 ik_max_word 再试一次，看词项变多对召回和精度的影响。")
                .build();
    }

    private static long hits(JsonNode res) {
        return res.path("hits").path("total").path("value").asLong();
    }
}
