package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
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
import java.util.Map;

/**
 * M7-1 分片与容量规划：从「能跑」到「跑得省」
 *
 * 单节点上分片不是越多越好：分片是并行度单位，也是开销单位
 * （每个分片一个 Lucene 实例、一份文件句柄、一份堆内结构、一次搜索请求）。
 *
 * 本关有两个对照，必须分开看：
 * · v1 vs v2 是**整体设计**对照（分片数、映射、分词、doc_values 同时变了），差异不能归因到分片；
 * · shard1 vs shard3 是**受控**对照（同 mapping、同数据、同查询，只差主分片数），
 *   只有这一组才能拿来说「分片数的影响」。
 */
@Component
public class M71ShardPlanning implements LevelModule {

    private static final String V1 = "eslab-products-v1";
    private static final String V2 = "eslab-products-v2";

    private final EsClient es;
    private final LabData lab;

    public M71ShardPlanning(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m7-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 受控实验：同一份 v2 定义，只把主分片数改成 1 与 3
        // 定义与目标条数都在 LabIndexes 里登记，这里只声明「要哪两个索引达标」
        lab.ensureByReindex(LabIndexes.SHARD1);
        lab.ensureByReindex(LabIndexes.SHARD3);
        // flush 之后再读 store.size：刚 refresh 完的段可能还没落盘，体积会偏小，两组不可比
        es.post("/" + LabIndexes.SHARD1 + "/_flush", null);
        es.post("/" + LabIndexes.SHARD3 + "/_flush", null);
        // @focus-end

        // @focus-start 同一份数据、同一个查询，只差「索引怎么建」：v1(1分片/动态) vs v2(3分片/显式)
        IndexStat s1 = stats(V1);
        IndexStat s2 = stats(V2);
        Timing.Sample t1 = Timing.sample(3, 1, round -> query(V1));
        Timing.Sample t2 = Timing.sample(3, 1, round -> query(V2));
        // @focus-end

        // @focus-start 受控组的测量口径与上面完全一致（同样的 query、5 轮取中位数、丢弃 1 轮预热）
        IndexStat c1 = stats(LabIndexes.SHARD1);
        IndexStat c3 = stats(LabIndexes.SHARD3);
        Timing.Sample q1 = Timing.sample(5, 1, round -> query(LabIndexes.SHARD1));
        Timing.Sample q3 = Timing.sample(5, 1, round -> query(LabIndexes.SHARD3));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("v1（动态映射）", s1.shards(), s1.docs(), mb(s1.bytes()), s1.segments(),
                num(t1.median()) + " ms", "整体设计对照"));
        rows.add(TableData.row("v2（显式映射）", s2.shards(), s2.docs(), mb(s2.bytes()), s2.segments(),
                num(t2.median()) + " ms", "整体设计对照"));
        rows.add(TableData.row("shard1（受控）", c1.shards(), c1.docs(), mb(c1.bytes()), c1.segments(),
                num(q1.median()) + " ms", "只差分片数"));
        rows.add(TableData.row("shard3（受控）", c3.shards(), c3.docs(), mb(c3.bytes()), c3.segments(),
                num(q3.median()) + " ms", "只差分片数"));

        return LevelResult.builder("受控组（同 mapping、同 " + String.format(Locale.ROOT, "%,d", c1.docs())
                        + " 条数据、同查询）：1 分片 " + num(q1.median()) + "ms / " + mb(c1.bytes())
                        + "，3 分片 " + num(q3.median()) + "ms / " + mb(c3.bytes())
                        + "；而 v1 与 v2 的差异混了映射、分词与 doc_values，不能算到分片头上")
                .metrics(
                        new Metric("受控：1 分片查询", num(q1.median()) + " ms", c1.segments() + " 段 · " + mb(c1.bytes())),
                        new Metric("受控：3 分片查询", num(q3.median()) + " ms", c3.segments() + " 段 · " + mb(c3.bytes())),
                        new Metric("受控：单分片体积", perShardMb(c1) + " MB", "1 分片全部数据"),
                        new Metric("受控：单分片体积(3)", perShardMb(c3) + " MB", "总量近似均分到 3 片"),
                        new Metric("v1 / v2 查询", num(t1.median()) + " / " + num(t2.median()) + " ms", "整体设计对照，不作归因"))
                .table(new TableData(
                        List.of("索引", "分片数", "文档数", "体积", "段数", "查询中位数", "对照类型"), rows))
                .json(Json.map("v1Samples", t1.runs(), "v2Samples", t2.runs(),
                        "shard1Samples", q1.runs(), "shard3Samples", q3.runs()))
                .notes(
                        "v1 与 v2 同时变了分片数、映射、分词器与 doc_values —— 这组差异只能说明「两种整体设计的不同」，不能说明「分片数的影响」。",
                        "受控组 shard1 / shard3 的定义完全同构，唯一差别是 number_of_shards。即便如此，本机 2 万条的绝对耗时才几毫秒，差多少都可能落在噪声里；要谈收益必须放大数据量后重测。",
                        "分片是「并行度单位」也是「开销单位」：再多分片，单节点也只有一颗 CPU 在跑；每多一个分片就多一份文件句柄与堆内结构。",
                        "规划口径（单分片 10–50GB）：20 万文档 60MB → 1 个分片就够；2 亿文档 60GB → 2–6 个分片。按「数据量 ÷ 30GB」起步，而不是按「节点数 × 2」。",
                        "分片数一旦确定不能改（reindex 除外），所以宁可初期偏少、后期用 reindex/shrink 调整。")
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
