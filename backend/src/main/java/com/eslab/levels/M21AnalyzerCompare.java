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

/**
 * M2-1 同一个中文句子，三种分词器切出什么
 *
 * 分词器决定了倒排索引里到底有哪些词项，也就决定了"用户搜这个词能不能召回"。
 * 中文场景下 standard 是逐字切分，几乎等于没有语义。
 */
@Component
public class M21AnalyzerCompare implements LevelModule {

    private static final String INDEX = "eslab-products-v2";
    private static final String TEXT = "小米无线蓝牙耳机主动降噪长续航";

    private final EsClient es;

    public M21AnalyzerCompare(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m2-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 对同一段文本跑三遍 _analyze，只换 analyzer
        JsonNode standardRes = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "standard", "text", TEXT));
        JsonNode smartRes = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "ik_smart", "text", TEXT));
        JsonNode maxRes = es.post("/" + INDEX + "/_analyze",
                Json.map("analyzer", "ik_max_word", "text", TEXT));
        // @focus-end

        List<String> standard = tokens(standardRes);
        List<String> ikSmart = tokens(smartRes);
        List<String> ikMax = tokens(maxRes);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("standard（默认）", standard.size(), String.join(" | ", standard),
                "逐字切分，无词义，\"耳机\"搜不到\"蓝牙耳机\""));
        rows.add(TableData.row("ik_smart", ikSmart.size(), String.join(" | ", ikSmart),
                "粗粒度，最长的词优先，索引体积小"));
        rows.add(TableData.row("ik_max_word", ikMax.size(), String.join(" | ", ikMax),
                "细粒度，穷举所有可成词组合，召回高但索引膨胀"));

        // 用 v2 的真实数据验证：不同分词器对同一查询的召回差异
        String q = "蓝牙耳机";
        JsonNode withSmart = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0,
                "query", Json.map("match", Json.map("title",
                        Json.map("query", q, "analyzer", "ik_smart")))));
        JsonNode withStandard = es.post("/" + INDEX + "/_search", Json.map(
                "size", 0,
                "query", Json.map("match", Json.map("title",
                        Json.map("query", q, "analyzer", "standard")))));

        long smartHits = withSmart.path("hits").path("total").path("value").asLong();
        long standardHits = withStandard.path("hits").path("total").path("value").asLong();

        return LevelResult.builder("同一个句子，standard 切出 " + standard.size()
                        + " 个单字、ik_smart 切出 " + ikSmart.size()
                        + " 个词、ik_max_word 切出 " + ikMax.size()
                        + " 个词——索引期用哪个，决定了查询期只能怎么查")
                .metrics(
                        new Metric("standard 词项数", standard.size(), "逐字"),
                        new Metric("ik_smart 词项数", ikSmart.size(), null),
                        new Metric("ik_max_word 词项数", ikMax.size(), null),
                        new Metric("查询召回对比", standardHits + " vs " + smartHits,
                                "standard 查询 vs ik_smart 查询"))
                .table(new TableData(List.of("分词器", "词项数", "切分结果", "特点"), rows))
                .json(Json.map("text", TEXT, "standard", standard, "ikSmart", ikSmart, "ikMax", ikMax))
                .notes(
                        "注意 standard 在处理中文时是\"每个汉字一个 token\"，所以它查\"蓝牙耳机\"实际上是四个单字各自匹配，召回大但精度极差（本关命中 "
                                + standardHits + " 条）。",
                        "常见配置：索引期 ik_max_word（尽可能多的词项，提高召回），查询期 ik_smart（避免查询串被切得过碎，提高精度）。v2 的 title 就是这么配的。",
                        "ik_max_word 会让索引变大（词项多、posting 多），这是用空间换召回，不是无脑选它。",
                        "英文场景另有一套：standard 对英文按词切分并小写化，不需要 IK；中英混排才需要 IK。")
                .build();
    }

    private static List<String> tokens(JsonNode res) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : res.path("tokens")) {
            out.add(t.path("token").asText());
        }
        return out;
    }
}
