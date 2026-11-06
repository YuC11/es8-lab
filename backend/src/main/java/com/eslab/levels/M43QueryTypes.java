package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.eslab.seed.SeedService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * M4-3 match / term / range 的适用边界
 *
 * 三种查询不是"写法不同"，而是三种语义：
 *   match → 会经过分析器，用于 text 字段的全文检索，算相关性分
 *   term  → 不分析，直接查词项，用于 keyword/数值/布尔
 *   range → 区间，用于数值与日期
 * 用错字段类型不会报错，只会静默返回 0 条——这是最难排查的一类问题。
 */
@Component
public class M43QueryTypes implements LevelModule {

    private static final String INDEX = SeedService.V2;

    private final EsClient es;

    public M43QueryTypes(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 同一份数据上的六种查询：注意哪些会静默返回 0
        Q matchText = q(Json.map("match", Json.map("title", "蓝牙耳机")));
        Q termKeyword = q(Json.map("term", Json.map("category", "数码影音")));
        Q termText = q(Json.map("term", Json.map("title", "蓝牙耳机")));
        Q matchKeyword = q(Json.map("match", Json.map("category", "数码影音")));
        Q rangePrice = q(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 500))));
        Q termPrice = q(Json.map("term", Json.map("price", 299)));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("match title=\"蓝牙耳机\"", "text", matchText.hits(),
                "查询串经 ik_smart 分析后匹配词项，并计算相关性分"));
        rows.add(TableData.row("term category=\"数码影音\"", "keyword", termKeyword.hits(),
                "整串精确等于，最快且可缓存"));
        rows.add(TableData.row("term title=\"蓝牙耳机\"", "text", termText.hits(),
                "✗ 不分析直接查词项，词典里没有这整个词项 → 0 条，且不报错"));
        rows.add(TableData.row("match category=\"数码影音\"", "keyword", matchKeyword.hits(),
                "keyword 的 search_analyzer 是 keyword，等于整串匹配；但如果查询串带空格就会被当成多词而失配"));
        rows.add(TableData.row("range price 100~500", "scaled_float", rangePrice.hits(),
                "数值走 BKD 树，区间查询的标准写法"));
        rows.add(TableData.row("term price=299", "scaled_float", termPrice.hits(),
                "数值 term 是\"精确等于\"语义，浮点字段上极易因精度差 0 条，优先用 range gte=x lte=x"));

        return LevelResult.builder("term 打在 text 上会静默返回 0 条（不报错），"
                        + "match 打在 keyword 上能查但不会分词——字段类型和查询方式必须成对使用")
                .metrics(
                        new Metric("match(title)", matchText.hits(), "全文检索正常路径"),
                        new Metric("term(category)", termKeyword.hits(), "keyword 精确匹配"),
                        new Metric("term(title)", termText.hits(), "静默 0，最坑的一种"),
                        new Metric("range(price)", rangePrice.hits(), null))
                .table(new TableData(List.of("查询", "字段类型", "命中", "语义与陷阱"), rows))
                .notes(
                        "判断顺序：先看字段类型（mapping），再决定查询类型；反过来做就会踩 term-on-text 这种静默失败。",
                        "数值字段做\"精确等于\"时用 range { gte: x, lte: x } 比 term 安全，尤其是 float/double/scaled_float。",
                        "match 打在 keyword 上不会报错，但也不会分词——中文词之间只要有一个字不同就查不到，容易误判成\"数据缺失\"。",
                        "底部控制台可以自己试：把 match 换成 term 再跑一次，观察命中数变成 0。")
                .build();
    }

    /** 一次 size:0 的计数查询，返回命中数与 ES 侧 took */
    private Q q(Map<String, Object> query) {
        JsonNode r = es.post("/" + INDEX + "/_search",
                Json.map("size", 0, "track_total_hits", true, "query", query));
        return new Q(
                r.path("hits").path("total").path("value").asLong(),
                r.path("took").asLong());
    }

    private record Q(long hits, long took) {
    }
}
