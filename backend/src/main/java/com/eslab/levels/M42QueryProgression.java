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
 * M4-2 从 match_all 开始，一次只加一个变量
 *
 * 查询不是一次写出来的，是一层层加上去的。本关把这句话变成七次请求：
 *   ①  {}                       → 什么都没有（total 被截在 10000，relation=gte）
 *   ①b + track_total_hits:true  → 同样的空查询，但这个数才可信
 *   ②  + size:0                 → 只要统计，不要文档
 *   ③  + term(category)         → 第一个条件：精确匹配
 *   ④  换成 match(title)        → 只换查询类型：全文检索
 *   ⑤  换成 range(price)        → 只换查询类型：区间
 *   ⑥  包成 bool{must,filter}   → 把两个条件组合起来
 *
 * 每一步只动一个地方，命中数的变化就能一一对上「这一步改了什么」。
 * 两条口径约束：① 与 ①b 专门用来暴露「默认截断」；② 之后一律 track_total_hits:true，
 * 且每步都用同一个稳定排序（sku 升序）取前 3 条 _id，这样「换了查法命中集就换了」看得见。
 */
@Component
public class M42QueryProgression implements LevelModule {

    private static final String INDEX = SeedService.V2;
    private static final List<Object> STABLE_SORT = Json.list(Json.map("sku", "asc"));

    private final EsClient es;

    public M42QueryProgression(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m4-2";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 七步：①/①b 只差 track_total_hits，②–⑥ 每次只加一个东西
        R empty = run(Json.map());
        R exactEmpty = run(Json.map("track_total_hits", true));
        R noHits = run(Json.map("size", 0, "track_total_hits", true));
        R term = run(step(Json.map("term", Json.map("category", "数码影音"))));
        R match = run(step(Json.map("match", Json.map("title", "蓝牙耳机"))));
        R range = run(step(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 500)))));
        R bool = run(step(Json.map("bool", Json.map(
                "must", Json.list(Json.map("match", Json.map("title", "蓝牙耳机"))),
                "filter", Json.list(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 500))))))));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("①", "{}", fmt(empty.total()), empty.relation(), ids(empty),
                "什么都没有：等价于 match_all，返回默认 10 条"));
        rows.add(TableData.row("①b", "+ track_total_hits:true", fmt(exactEmpty.total()), exactEmpty.relation(),
                ids(exactEmpty), "只加这一个参数：同一个空查询，数字从截断值变成精确值"));
        rows.add(TableData.row("②", "+ size:0", fmt(noHits.total()), noHits.relation(),
                "（0 条）", "只要统计，不要文档"));
        rows.add(TableData.row("③", "+ query.term(category)", fmt(term.total()), term.relation(),
                ids(term), "加上 query：第一个条件，keyword 精确匹配"));
        rows.add(TableData.row("④", "→ query.match(title)", fmt(match.total()), match.relation(),
                ids(match), "只换查询类型：会分词，适合全文检索"));
        rows.add(TableData.row("⑤", "→ query.range(price)", fmt(range.total()), range.relation(),
                ids(range), "只换查询类型：数值区间"));
        rows.add(TableData.row("⑥", "→ bool{must, filter}", fmt(bool.total()), bool.relation(),
                ids(bool), "把两个条件组合：相关性进 must，过滤进 filter"));

        long minOfParts = Math.min(match.total(), range.total());

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("① 空请求体", empty.raw());
        json.put("①b 精确总数", exactEmpty.raw());
        json.put("⑥ bool 组合", bool.raw());

        return LevelResult.builder("从「什么都没有」到一个完整的 bool，一共只加了 5 个东西："
                        + "size、query、查询类型、第二个条件、bool 外壳。同样的空查询，不写 track_total_hits 时 total 是 "
                        + fmt(empty.total()) + "（" + empty.relation() + "），写上才是 " + fmt(exactEmpty.total())
                        + "（" + exactEmpty.relation() + "）—— 所以后面每一步都统一带上它")
                .metrics(
                        new Metric("① 默认 total", fmt(empty.total()) + " / " + empty.relation(),
                                "被截在 10000，只是下限"),
                        new Metric("①b 精确 total", fmt(exactEmpty.total()) + " / " + exactEmpty.relation(),
                                "同一个空查询，只多一个参数"),
                        new Metric("③ term(category)", fmt(term.total()), "keyword 精确匹配"),
                        new Metric("④ match(title)", fmt(match.total()), "会分析查询串"),
                        new Metric("⑤ range(price 100~500)", fmt(range.total()), "数值区间"),
                        new Metric("⑥ bool(must+filter)", fmt(bool.total()),
                                "≤ min(④,⑤) = " + fmt(minOfParts) + " 才合理"))
                .table(new TableData(List.of("步骤", "请求体", "命中", "relation", "前 3 条 _id", "这一步只加了什么"), rows))
                .json(json)
                .notes(
                        "空的 {} 是合法的：ES 会当成 match_all，但 total 只保证到 10000（relation=gte）—— ① 与 ①b 就是这一条的证据。",
                        "想只要总数就写 size: 0 —— 这也是所有聚合、计数请求的标准开头。",
                        "term → match → range 是三种「叶子查询」，写法都是 {\"查询类型\": {\"字段\": 值}}。",
                        "bool 是「复合查询」，它不查字段，只负责把多个叶子查询按 must/filter/should/must_not 组合起来。",
                        "命中集会变：③ 到 ④ 只是换了查询类型，前 3 条 _id 就完全不同；⑥ 是把 ④ 的条件和 ⑤ 的条件取交集，所以 total 必然 ≤ 两者较小值。",
                        "底部控制台里把这七条依次跑一遍，比读十遍文档管用。")
                .build();
    }

    /** ③ 之后统一口径：精确 total + 稳定排序 + 取 3 条用于比对命中集 */
    private static Map<String, Object> step(Map<String, Object> query) {
        return Json.map("size", 3, "track_total_hits", true, "sort", STABLE_SORT,
                "_source", Json.list("sku"), "query", query);
    }

    private R run(Map<String, Object> body) {
        JsonNode r = es.post("/" + INDEX + "/_search", body);
        JsonNode hits = r.path("hits");
        List<String> ids = new ArrayList<>();
        for (JsonNode h : hits.path("hits")) {
            ids.add(h.path("_id").asText());
        }
        return new R(
                hits.path("total").path("value").asLong(),
                hits.path("total").path("relation").asText("eq"),
                hits.path("hits").size(),
                ids,
                r);
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String ids(R r) {
        if (r.ids().isEmpty()) return "（无）";
        int n = Math.min(3, r.ids().size());
        String head = String.join(" / ", r.ids().subList(0, n));
        return r.ids().size() > n ? head + " …（共 " + r.ids().size() + " 条）" : head;
    }

    private record R(long total, String relation, int returned, List<String> ids, JsonNode raw) {
    }
}
