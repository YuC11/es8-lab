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
 * 查询不是一次写出来的，是一层层加上去的。本关把这句话变成六次请求：
 *   ① {}                       → 什么都没有
 *   ② {"size": 0}              → 加上「只要总数」
 *   ③ + term(category)         → 加上第一个条件：精确匹配
 *   ④ 换成 match(title)        → 只换查询类型：全文检索
 *   ⑤ 换成 range(price)        → 只换查询类型：区间
 *   ⑥ 包成 bool{must,filter}   → 把两个条件组合起来
 * 每一步只动一个地方，命中数的变化就能一一对上「这一步改了什么」。
 */
@Component
public class M42QueryProgression implements LevelModule {

    private static final String INDEX = SeedService.V2;

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
        // @focus-start 六步，每一步只在前一步的基础上加一个东西
        R empty = run(Json.map());
        R noHits = run(Json.map("size", 0));
        R term = run(Json.map("size", 0, "track_total_hits", true,
                "query", Json.map("term", Json.map("category", "数码影音"))));
        R match = run(Json.map("size", 0, "track_total_hits", true,
                "query", Json.map("match", Json.map("title", "蓝牙耳机"))));
        R range = run(Json.map("size", 0, "track_total_hits", true,
                "query", Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 200)))));
        R bool = run(Json.map("size", 0, "track_total_hits", true,
                "query", Json.map("bool", Json.map(
                        "must", Json.list(Json.map("match", Json.map("title", "蓝牙耳机"))),
                        "filter", Json.list(Json.map("range", Json.map("price", Json.map("gte", 100, "lte", 500))))))));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("①", "{}", String.format(Locale.ROOT, "%,d", empty.total()),
                empty.returned() + " 条", "什么都没有：等价于 match_all，返回默认 10 条"));
        rows.add(TableData.row("②", "{\"size\": 0}", String.format(Locale.ROOT, "%,d", noHits.total()),
                "0 条", "只加了 size：不要文档，只要统计"));
        rows.add(TableData.row("③", "+ query.term(category)", String.format(Locale.ROOT, "%,d", term.total()),
                "0 条", "加上 query：第一个条件，keyword 精确匹配"));
        rows.add(TableData.row("④", "→ query.match(title)", String.format(Locale.ROOT, "%,d", match.total()),
                "0 条", "只换查询类型：会分词，适合全文检索"));
        rows.add(TableData.row("⑤", "→ query.range(price)", String.format(Locale.ROOT, "%,d", range.total()),
                "0 条", "只换查询类型：数值区间"));
        rows.add(TableData.row("⑥", "→ bool{must, filter}", String.format(Locale.ROOT, "%,d", bool.total()),
                "0 条", "把两个条件组合：相关性进 must，过滤进 filter"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("① 空请求体", empty.raw());
        json.put("⑥ bool 组合", bool.raw());

        return LevelResult.builder("从「什么都没有」到「一个完整的 bool」，一共只加了 5 个东西："
                        + "size、query、查询类型、第二个条件、bool 外壳 —— 每一步的命中数都对应得上你改的那一处")
                .metrics(
                        new Metric("③ term(category)", String.format(Locale.ROOT, "%,d", term.total()), "keyword 精确匹配"),
                        new Metric("④ match(title)", String.format(Locale.ROOT, "%,d", match.total()), "会分析查询串"),
                        new Metric("⑤ range(price 100~200)", String.format(Locale.ROOT, "%,d", range.total()), "数值区间"),
                        new Metric("⑥ bool(must+filter)", String.format(Locale.ROOT, "%,d", bool.total()), "两个条件同时满足"))
                .table(new TableData(List.of("步骤", "请求体", "命中", "返回文档", "这一步只加了什么"), rows))
                .json(json)
                .notes(
                        "空的 {} 是合法的：ES 会当成 match_all，返回默认的 10 条、total 只给到 10000 的下限。",
                        "想只要总数就写 size: 0 —— 这也是所有聚合、计数请求的标准开头。",
                        "term → match → range 是三种「叶子查询」，写法都是 {\"查询类型\": {\"字段\": 值}}。",
                        "bool 是「复合查询」，它不查字段，只负责把多个叶子查询按 must/filter/should/must_not 组合起来。",
                        "底部控制台里把这六条依次跑一遍，比读十遍文档管用。")
                .build();
    }

    private R run(Map<String, Object> body) {
        JsonNode r = es.post("/" + INDEX + "/_search", body);
        JsonNode hits = r.path("hits");
        return new R(
                hits.path("total").path("value").asLong(),
                hits.path("hits").size(),
                r);
    }

    private record R(long total, int returned, JsonNode raw) {
    }
}
