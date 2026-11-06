package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.lib.Timing;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * M7-1 分片与容量规划：从「能跑」到「跑得省」
 *
 * 单节点上分片不是越多越好：分片是并行度单位，也是开销单位
 * （每个分片一个 Lucene 实例、一份文件句柄、一份堆内结构、一次搜索请求）。
 */
@Component
public class M71ShardPlanning implements LevelModule {

    private static final String V1 = "eslab-products-v1";
    private static final String V2 = "eslab-products-v2";

    private final EsClient es;

    public M71ShardPlanning(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m7-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 同一份数据、同一个查询，只差「索引怎么建」：v1(1分片/动态) vs v2(3分片/显式)
        IndexStat s1 = stats(V1);
        IndexStat s2 = stats(V2);
        Timing.Sample t1 = Timing.sample(3, 1, round -> query(V1));
        Timing.Sample t2 = Timing.sample(3, 1, round -> query(V2));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("v1（动态映射）", s1.shards(), s1.docs(), mb(s1.bytes()), s1.segments(),
                num(t1.median()) + " ms"));
        rows.add(TableData.row("v2（显式映射）", s2.shards(), s2.docs(), mb(s2.bytes()), s2.segments(),
                num(t2.median()) + " ms"));

        return LevelResult.builder("同样 " + String.format(Locale.ROOT, "%,d", s1.docs())
                        + " 条文档：v1 单分片 " + mb(s1.bytes()) + " / " + s1.segments()
                        + " 段 / 查询 " + num(t1.median()) + "ms；v2 三分片 " + mb(s2.bytes()) + " / "
                        + s2.segments() + " 段 / 查询 " + num(t2.median()) + "ms")
                .metrics(
                        new Metric("v1 单分片查询", num(t1.median()) + " ms", s1.segments() + " 段"),
                        new Metric("v2 三分片查询", num(t2.median()) + " ms", s2.segments() + " 段"),
                        new Metric("v1 体积 / 分片", perShardMb(s1) + " MB", null),
                        new Metric("v2 体积 / 分片", perShardMb(s2) + " MB", null))
                .table(new TableData(
                        List.of("索引", "分片数", "文档数", "体积", "段数", "查询中位数"), rows))
                .json(Json.map("v1Samples", t1.runs(), "v2Samples", t2.runs()))
                .notes(
                        "本机单节点、单分片数据量只有 60–90MB，三分片带来的并行收益有限，但元数据与请求开销是实打实的——这就是\"分片不是越多越好\"的最小可观测版本。",
                        "规划口径（单分片 10–50GB）：20 万文档 60MB → 1 个分片就够；2 亿文档 60GB → 2–6 个分片。按\"数据量 ÷ 30GB\"起步，而不是按\"节点数 × 2\"。",
                        "分片数一旦确定不能改（reindex 除外），所以宁可初期偏少、后期用 reindex/shrink 调整，也不要一上来就 12 分片。",
                        "单节点上副本只能设 0：设 1 会导致副本分片永远 unassigned，集群一直 yellow。")
                .build();
    }

    /** 索引的体积、段数、文档数、分片数 */
    private IndexStat stats(String index) {
        JsonNode s = es.get("/" + index + "/_stats/docs,store,segments");
        JsonNode prim = s.path("indices").path(index).path("primaries");
        JsonNode settings = es.get("/" + index + "/_settings");
        long shards = settings.path(index).path("settings").path("index").path("number_of_shards").asLong();
        return new IndexStat(
                prim.path("docs").path("count").asLong(),
                prim.path("store").path("size_in_bytes").asLong(),
                prim.path("segments").path("count").asLong(),
                shards);
    }

    private double query(String index) {
        return es.post("/" + index + "/_search", Json.map(
                "size", 100,
                "track_total_hits", false,
                "_source", Json.list("sku", "title", "price"),
                "query", Json.map("range", Json.map("price", Json.map("gte", 100))),
                "sort", Json.list(Json.map("price", "asc")))).path("took").asDouble();
    }

    private static String perShardMb(IndexStat s) {
        return String.format(Locale.ROOT, "%.1f", (double) s.bytes() / s.shards() / 1024 / 1024);
    }

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", (double) bytes / 1024 / 1024);
    }

    private static String mb0(long bytes) {
        return String.format(Locale.ROOT, "%.0f", (double) bytes / 1024 / 1024);
    }

    /** 与 JS 的 number 打印保持一致：整数值不带小数点 */
    private static String num(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return String.valueOf((long) v);
        return String.valueOf(v);
    }

    private record IndexStat(long docs, long bytes, long segments, long shards) {
    }
}
