package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m8-4：段合并与滚动索引。
 *
 * 两个常被误解的点：
 *   · forcemerge 的「1 段」是「每个分片 1 段」，3 分片合并完仍然是 3 段；
 *   · rollover 不是自动分片，它只在条件满足时新建一个索引，并把写别名挪过去。
 */
@Component
public class M84ForceMergeRollover implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M84ForceMergeRollover(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-4";
    }

    @Override
    public LevelResult run() throws Exception {
        // 段合并的对象：v3（3 分片）；条数不符就整条重建，保证起点是同一份 2 万条
        lab.ensureByReindex(LabIndexes.V3);

        long segBefore = lab.segmentCount(LabIndexes.V3);

        // @focus-start max_num_segments=1 表示「每个分片合并成 1 段」
        JsonNode merge = es.post("/" + LabIndexes.V3 + "/_forcemerge?max_num_segments=1", null, 120_000);
        // @focus-end

        long segAfter = segBefore;
        for (int i = 0; i < 10; i++) {
            Thread.sleep(300);
            segAfter = lab.segmentCount(LabIndexes.V3);
            if (segAfter > 0 && segAfter != segBefore) break;
        }

        // @focus-start 滚动索引：每次先把 -000001/-000002 全部清掉，保证重复运行不会滚到 -000003
        for (String name : lab.indicesOf(LabIndexes.LIVE_ALIAS + "-*")) {
            lab.delete(name);
        }
        lab.rebuild(LabIndexes.LIVE_1,
                idx -> lab.bulkGenerate(idx, 0, LabIndexes.LIVE_DOCS, null));
        // @focus-end

        long liveDocs = lab.indexCount(LabIndexes.LIVE_1);

        // @focus-start 条件满足才滚；返回 rolled_over 与新旧索引名
        JsonNode rollover = es.post("/" + LabIndexes.LIVE_ALIAS + "/_rollover",
                Map.of("conditions", Map.of("max_docs", 4_000)));
        // @focus-end

        // 切完之后写一条，验证它落在新索引上
        Map<String, Object> doc = new LinkedHashMap<>(lab.productDoc(0));
        doc.put("sku", "ROLL-1");
        doc.put("title", "滚动索引验证商品");
        JsonNode write = es.put("/" + LabIndexes.LIVE_ALIAS + "/_doc/ROLL-1?refresh=true", doc);

        long newDocs = lab.indexCount(LabIndexes.LIVE_2);
        List<Map<String, Object>> aliases = lab.aliasRows(LabIndexes.LIVE_ALIAS);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("合并前段数", fmt(segBefore), "3 个分片各自累积出的段"));
        rows.add(TableData.row("合并后段数", fmt(segAfter), "理想值是「分片数 × 1」，即 3 而不是 1"));
        rows.add(TableData.row(LabIndexes.LIVE_1 + " 条数", fmt(liveDocs), "rollover 前的写索引"));
        rows.add(TableData.row(LabIndexes.LIVE_2 + " 条数", fmt(newDocs), "rollover 后写入的那 1 条"));
        rows.add(TableData.row("新文档落到", write.path("_index").asText("?"), "写别名已指向新索引"));

        boolean rolled = rollover.path("rolled_over").asBoolean(false);

        // 重复跑这一关时段数往往已经是最小值：此时「从 3 段合到 3 段」会读成「什么都没发生」，
        // 但它其实正好证明了段合并是幂等的 —— 两个分支都说清楚，别让文案误导。
        boolean merged = segAfter < segBefore;
        String head = merged
                ? "forcemerge 把 v3 从 " + fmt(segBefore) + " 段合到 " + fmt(segAfter)
                + " 段（3 分片 × 每片 1 段 —— max_num_segments=1 是「每分片 1 段」，不是「全索引 1 段」）"
                : "v3 已经是 " + fmt(segBefore) + " 段（3 分片 × 每片 1 段），forcemerge 无事可做"
                + " —— 段合并是幂等的，再合一次不会产生更多收益";

        return LevelResult.builder(head
                        + "；rollover 在超过 4000 条时把写索引从 -000001 换到 -000002")
                .metrics(
                        new Metric("合并前段数", fmt(segBefore), "3 个分片各自累积出的段之和"),
                        new Metric("合并后段数", fmt(segAfter),
                                merge.path("_shards").path("failed").asInt(0) != 0 ? "有分片失败"
                                        : merged ? "全部分片成功" : "本来就是最小值"),
                        new Metric("rollover", rolled ? "已滚动" : "未滚动",
                                rolled ? "-000001 → -000002" : "条件未满足"),
                        new Metric("写别名指向", aliases.stream()
                                .filter(a -> Boolean.TRUE.equals(a.get("writeIndex")))
                                .map(a -> String.valueOf(a.get("index")))
                                .findFirst().orElse("?"), "新写入进这个索引"))
                .table(TableData.of(List.of("项目", "数值", "说明"), rows))
                .json(Map.of("forcemerge", merge, "rollover", rollover, "write", write))
                .notes(
                        "forcemerge 的收益是查询时少扫几个段；代价是大量磁盘 IO，且合并期间几乎吃满 IO，生产上放在低峰期做",
                        "不要对还在频繁写入的索引做 forcemerge —— 新写入会立刻产生新段，白干",
                        "rollover 的三种条件（max_docs / max_age / max_size）只决定「要不要新建」，索引名里的 -000001 是它自动加的")
                .build();
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
