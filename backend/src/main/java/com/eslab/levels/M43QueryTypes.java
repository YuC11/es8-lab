package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
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
import java.util.Locale;
import java.util.Map;

/**
 * M4-3 match / term / range 的适用边界
 *
 * 三种查询不是"写法不同"，而是三种语义：
 *   match → 会经过分析器，用于 text 字段的全文检索，算相关性分
 *   term  → 不分析，直接查词项，用于 keyword/数值/布尔
 *   range → 区间，用于数值与日期
 *
 * 本关的十次请求分成两组：
 *   · 静默组：写错了也不报错，只是返回 0 条（term 打 text、term 打未知字段、term 打 object、exists 未知字段）
 *   · 报错组：类型对不上直接 400（term 给 price 传字符串 "abc"）
 * 两组都重要 —— 只有知道哪一类会报错，才分得清"查不到"和"写错了"。
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
        // 取一条真实文档，用它的完整标题做「整串相等」的对照
        JsonNode sample = es.post("/" + INDEX + "/_search", Json.map(
                "size", 1, "sort", Json.list(Json.map("sku", "asc")), "_source", Json.list("title")));
        String sampleTitle = sample.path("hits").path("hits").path(0).path("_source").path("title").asText("");

        // @focus-start 同一份数据上的十种查询：注意哪些会静默返回 0、哪些会真的报错
        Q matchText = q(Json.map("match", Json.map("title", "蓝牙耳机")));
        Q termKeyword = q(Json.map("term", Json.map("category", "数码影音")));
        Q termText = q(Json.map("term", Json.map("title", "蓝牙耳机")));
        Q matchKeyword = q(Json.map("match", Json.map("category", "数码影音")));
        Q rangePrice = q(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 500))));
        Q termPrice = q(Json.map("term", Json.map("price", 299)));
        Q termWholeTitle = q(Json.map("term", Json.map("title.keyword", sampleTitle)));
        Q matchNumeric = q(Json.map("match", Json.map("status", "1")));
        Q termMissingField = q(Json.map("term", Json.map("no_such_field", "x")));
        Q termObject = q(Json.map("term", Json.map("specs", "x")));
        Q termPriceBadType = q(Json.map("term", Json.map("price", "abc")));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("match title=\"蓝牙耳机\"", "text", hits(matchText),
                "查询串经 ik_smart 分析后匹配词项，并计算相关性分"));
        rows.add(TableData.row("term category=\"数码影音\"", "keyword", hits(termKeyword),
                "整串精确等于，最快且可缓存"));
        rows.add(TableData.row("term title=\"蓝牙耳机\"", "text", hits(termText),
                "✗ 不分析直接查词项，词典里没有这整个词项 → 0 条，且不报错"));
        rows.add(TableData.row("match category=\"数码影音\"", "keyword", hits(matchKeyword),
                "keyword 的 search_analyzer 是 keyword，等于整串匹配；查询串带空格就会被当成多词而失配"));
        rows.add(TableData.row("range price 100~500", "scaled_float", hits(rangePrice),
                "数值走 BKD 树，区间查询的标准写法"));
        rows.add(TableData.row("term price=299（数字）", "scaled_float", hits(termPrice),
                "数值 term 是\"精确等于\"语义；浮点字段上优先用 range gte=x lte=x"));
        rows.add(TableData.row("term title.keyword=\"...\"", "keyword 子字段", hits(termWholeTitle),
                "整串相等：只有和这条标题一模一样的文档才命中 —— 是 1 条，不是几千条"));
        rows.add(TableData.row("match status=\"1\"（字符串）", "integer", hits(matchNumeric),
                "数值字段上 match 也能用：查询串被当成单个词项去匹配 long 值，但别依赖它，语义是模糊的"));
        rows.add(TableData.row("term no_such_field=\"x\"", "（不存在）", hits(termMissingField),
                "✗ 字段不存在也不报错，静默 0 条 —— 拼错字段名最难查"));
        rows.add(TableData.row("term specs=\"x\"", "object", hits(termObject),
                "✗ 对 object 直接 term 同样静默 0 条；要查子字段得写 specs.color"));
        rows.add(TableData.row("term price=\"abc\"", "scaled_float", hits(termPriceBadType),
                "✓ 这一类才报错：400 failed to create query: For input string: \"abc\""));

        long silent = 0;
        for (Q x : List.of(termText, termMissingField, termObject)) {
            if (x.ok() && x.hits() == 0) silent++;
        }

        return LevelResult.builder("term 打在 text 上、打在不存在的字段上、打在 object 上，三种都是静默 0 条（不报错）；"
                        + "只有把字符串传给数值字段（price=\"abc\"）才会 400 —— 所以「查不到」时先怀疑字段与写法，别怀疑数据丢了")
                .metrics(
                        new Metric("match(title)", fmt(matchText.hits()), "全文检索正常路径"),
                        new Metric("term(category)", fmt(termKeyword.hits()), "keyword 精确匹配"),
                        new Metric("term(title)", fmt(termText.hits()), "静默 0，最坑的一种"),
                        new Metric("term(title.keyword) 整串", fmt(termWholeTitle.hits()), "只有 1 条：整串相等"),
                        new Metric("静默 0 的写法", silent + " / 3", "都不报错，只返回空结果"),
                        new Metric("term(price=\"abc\")", termPriceBadType.ok()
                                        ? fmt(termPriceBadType.hits()) : "400 " + termPriceBadType.errorType(),
                                "类型真的对不上才会报错"))
                .table(new TableData(List.of("查询", "字段类型", "命中", "语义与陷阱"), rows))
                .json(Json.map("整串标题", sampleTitle,
                        "样本", Json.map("term.title", termText.raw(), "term.price.abc", termPriceBadType.raw())))
                .notes(
                        "判断顺序：先看字段类型（mapping），再决定查询类型；反过来做就会踩 term-on-text 这种静默失败。",
                        "数值字段做\"精确等于\"时用 range { gte: x, lte: x } 比 term 安全，尤其是 float/double/scaled_float。",
                        "match 打在 keyword 上不会报错，但也不会分词——中文词之间只要有一个字不同就查不到，容易误判成\"数据缺失\"。",
                        "字段名写错、对 object 直接 term：这两类同样静默 0 条。排查「搜不到」时先 `GET /索引/_mapping` 确认字段真实存在且类型对得上。",
                        "唯一会报错的是类型不匹配（字符串传给数值字段），报错原文在表格最后一行 —— 记住这条界线，才不会把 0 条都当成 bug。")
                .build();
    }

    /** 一次 size:0 的计数查询：成功记命中数，失败记错误类型 */
    private Q q(Map<String, Object> query) {
        try {
            JsonNode r = es.post("/" + INDEX + "/_search",
                    Json.map("size", 0, "track_total_hits", true, "query", query));
            return new Q(true,
                    r.path("hits").path("total").path("value").asLong(),
                    r.path("took").asLong(),
                    "", r);
        } catch (EsException e) {
            return new Q(false, 0, 0, errorType(e), null);
        }
    }

    /** 异常类型在 payload 里（EsException 保留了原始响应），取最内层的 root_cause */
    private static String errorType(EsException e) {
        JsonNode payload = e.payload();
        String type = payload == null ? "" : payload.path("error").path("type").asText("");
        if (!type.isEmpty()) return type;
        JsonNode cause = payload == null ? null : payload.path("error").path("root_cause").path(0).path("type");
        if (cause != null && !cause.isMissingNode()) return cause.asText();
        return "HTTP " + e.status();
    }

    private static String hits(Q x) {
        return x.ok() ? fmt(x.hits()) : "400 " + x.errorType();
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private record Q(boolean ok, long hits, long took, String errorType, JsonNode raw) {
    }
}
