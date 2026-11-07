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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M4-5 operator / minimum_should_match / boost：谁改召回，谁改排序
 *
 * 本关把五个参数分成两组，用同一张表并排：
 *   改「谁算命中」（召回）：operator、minimum_should_match
 *   改「谁排前面」（排序）：boost
 *
 * 关键证据是最后一列：前三组的命中数会变，boost 那一组命中数一模一样、但 Top3 顺序变了。
 * 只有把 Top3 摆出来，「boost 不改变命中集」才不是一句口号。
 */
@Component
public class M45QueryParams implements LevelModule {

    private static final String INDEX = SeedService.V2;
    private static final String THREE_WORDS = "蓝牙 降噪 耳机";

    private final EsClient es;

    public M45QueryParams(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-5";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 前三条只改「命中条件」，第四条只改「排序权重」
        R or = run(Json.map("match", Json.map("title", THREE_WORDS)));
        R msm2 = run(Json.map("match", Json.map("title",
                Json.map("query", THREE_WORDS, "minimum_should_match", "2"))));
        R and = run(Json.map("match", Json.map("title",
                Json.map("query", THREE_WORDS, "operator", "and"))));
        R plain = run(Json.map("bool", Json.map("should", Json.list(
                Json.map("term", Json.map("tags", "爆款")),
                Json.map("term", Json.map("category", "数码影音"))))));
        R boosted = run(Json.map("bool", Json.map("should", Json.list(
                Json.map("term", Json.map("tags", Json.map("value", "爆款", "boost", 5))),
                Json.map("term", Json.map("category", "数码影音"))))));
        // @focus-end

        boolean sameHitSet = plain.total() == boosted.total();
        boolean orderChanged = !plain.topIds().isEmpty() && !boosted.topIds().isEmpty()
                && !plain.topIds().get(0).equals(boosted.topIds().get(0));

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("operator: or（默认）", "match title=" + THREE_WORDS,
                fmt(or.total()), score(or.maxScore()), tops(or),
                "命中任一关键词即算命中 —— 召回最大、精度最低"));
        rows.add(TableData.row("minimum_should_match: 2", "至少命中 3 个词中的 2 个",
                fmt(msm2.total()), score(msm2.maxScore()), tops(msm2),
                "按比例收紧：介于 or 与 and 之间"));
        rows.add(TableData.row("operator: and", "三个词必须全中",
                fmt(and.total()), score(and.maxScore()), tops(and),
                "召回最小、精度最高，最容易「搜不到」"));
        rows.add(TableData.row("boost: 1（默认）", "bool.should 两个条件不加权",
                fmt(plain.total()), score(plain.maxScore()), tops(plain),
                "基准：记下这个顺序"));
        rows.add(TableData.row("boost: 5", "tags 条件权重 ×5",
                fmt(boosted.total()), score(boosted.maxScore()), tops(boosted),
                "命中数完全不变，Top1 换了人 —— 改变的是排序，不是命中"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("operator: or", or.raw());
        json.put("minimum_should_match: 2", msm2.raw());
        json.put("operator: and", and.raw());
        json.put("boost: 1", plain.raw());
        json.put("boost: 5", boosted.raw());

        return LevelResult.builder("operator 与 minimum_should_match 改的是「谁算命中」："
                        + fmt(or.total()) + " → " + fmt(msm2.total()) + " → " + fmt(and.total())
                        + "；boost 一行命中都不会动（" + fmt(plain.total()) + " → " + fmt(boosted.total())
                        + "），最高分从 " + score(plain.maxScore()) + " 抬到 " + score(boosted.maxScore())
                        + (orderChanged
                        ? "，Top1 也从「" + firstTitle(plain) + "」换成了「" + firstTitle(boosted) + "」"
                        : "，但本机 Top3 顺序没变 —— 加权生效了，只是头部文档本来就在最前面"))
                .metrics(
                        new Metric("operator: or", fmt(or.total()), "默认行为"),
                        new Metric("minimum_should_match: 2", fmt(msm2.total()), "三词里至少两个"),
                        new Metric("operator: and", fmt(and.total()), "三词全中"),
                        new Metric("boost 前后命中数", fmt(plain.total()) + " → " + fmt(boosted.total()),
                                sameHitSet ? "完全一致：boost 不改命中集" : "异常：命中集变了"),
                        new Metric("boost 前后 Top1", orderChanged
                                        ? firstTitle(plain) + " → " + firstTitle(boosted)
                                        : "未变（" + firstTitle(plain) + "）",
                                orderChanged ? "顺序变了：这才是 boost 的作用"
                                        : "分数被抬高但头部未换位，本机未测出顺序变化"))
                .table(new TableData(List.of("参数", "写法", "命中", "最高分", "Top3（按当前分排序）", "它改变了什么"), rows))
                .json(json)
                .notes(
                        "match 默认是 operator: or，只要命中任意一个词就算 —— 中文短句经常因此召回一堆不相关的文档。",
                        "minimum_should_match 支持百分比（如 75%），比 operator 更灵活：词数变化时阈值跟着变。",
                        "boost 只改变打分权重，不会让原本不命中的文档命中，也不会让命中的掉出去 —— 它是排序工具，不是过滤工具。",
                        "把 Top3 列出来就能看见：boost 前后命中数一致，但排第一的文档换了，这说明变化发生在打分阶段而不是匹配阶段。",
                        "真正要过滤「必须有」的条件，用 bool.filter；boost 只用于「都命中时谁排前面」。",
                        "同名参数在不同查询里位置不同：match 的子参数写在 {query, operator} 里，term 的写在 {value, boost} 里。")
                .build();
    }

    /**
     * 一次查询取三条：size=3 保留真实打分顺序（不传 sort），
     * 同时用 track_total_hits 拿精确命中数。
     */
    private R run(Map<String, Object> query) {
        JsonNode r = es.post("/" + INDEX + "/_search", Json.map(
                "size", 3,
                "track_total_hits", true,
                "_source", Json.list("sku", "title"),
                "query", query));
        JsonNode hits = r.path("hits");
        JsonNode max = hits.path("max_score");
        List<String> topIds = new ArrayList<>();
        List<String> topTitles = new ArrayList<>();
        for (JsonNode h : hits.path("hits")) {
            topIds.add(h.path("_id").asText());
            topTitles.add(h.path("_source").path("title").asText());
        }
        return new R(
                hits.path("total").path("value").asLong(),
                max.isNumber() ? max.asDouble() : null,
                topIds, topTitles, r);
    }

    private static String tops(R r) {
        if (r.topTitles().isEmpty()) return "（无命中）";
        return String.join(" · ", r.topTitles());
    }

    private static String firstTitle(R r) {
        return r.topTitles().isEmpty() ? "（无）" : r.topTitles().get(0);
    }

    /** 分数保留三位；null 说明没有命中 */
    private static String score(Double v) {
        return v == null ? "—" : String.format(Locale.ROOT, "%.3f", v);
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private record R(long total, Double maxScore, List<String> topIds, List<String> topTitles, JsonNode raw) {
    }
}
