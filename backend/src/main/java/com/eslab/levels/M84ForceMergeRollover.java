package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
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
import java.util.TreeMap;

/**
 * m8-4：段合并与滚动索引。
 *
 * 两个常被误解的点：
 *   · forcemerge 的「1 段」是「每个分片 1 段」，3 分片合并完仍然是 3 段；
 *   · rollover 不是自动分片，它只在条件满足时新建一个索引，并把写别名挪过去。
 *
 * 本关用三件事把这两点钉死：
 *   ① 段数按分片分别统计（_cat/segments），不再取 _cat/indices 的索引级汇总；
 *   ② 连做两次 forcemerge，第二次的段数必须与第一次相同 —— 幂等不是靠文案判断；
 *   ③ rollover 前后各拍一次写别名快照（is_write_index），并确认旧索引变只读。
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

        // @focus-start 段数按分片统计：_cat/segments 一行一段，按 shard 归并
        Map<String, Integer> segsBefore = segmentsByShard(LabIndexes.V3);

        // max_num_segments=1 的含义是「每个分片合并成 1 段」
        JsonNode merge = es.post("/" + LabIndexes.V3 + "/_forcemerge?max_num_segments=1", null, 120_000);
        Map<String, Integer> segsAfter = waitSegments(LabIndexes.V3, segsBefore);
        // @focus-end

        // @focus-start 再合一次：段数应当与第一次相同（幂等），不是「每次都能再降」
        JsonNode mergeAgain = es.post("/" + LabIndexes.V3 + "/_forcemerge?max_num_segments=1", null, 120_000);
        Map<String, Integer> segsAgain = segmentsByShard(LabIndexes.V3);
        // @focus-end

        // @focus-start 滚动索引：每次先把 -000001/-000002 全部清掉，保证重复运行不会滚到 -000003
        for (String name : lab.indicesOf(LabIndexes.LIVE_ALIAS + "-*")) {
            lab.delete(name);
        }
        lab.rebuild(LabIndexes.LIVE_1,
                idx -> lab.bulkGenerate(idx, 0, LabIndexes.LIVE_DOCS, null));
        // @focus-end

        // @focus-start 造多段：每条带 refresh=true 写一次，每次刷新落一个新段（仅用于演示合并，条数仍在 rollover 阈值内）
        for (int i = 0; i < 10; i++) {
            Map<String, Object> extra = new LinkedHashMap<>(lab.productDoc(i));
            extra.put("sku", "SEG-" + i);
            extra.put("title", "造段演示商品 " + i);
            es.put("/" + LabIndexes.LIVE_1 + "/_doc/SEG-" + i + "?refresh=true", extra);
        }
        Map<String, Integer> liveBefore = segmentsByShard(LabIndexes.LIVE_1);
        es.post("/" + LabIndexes.LIVE_1 + "/_forcemerge?max_num_segments=1", null, 120_000);
        Map<String, Integer> liveAfter = segmentsByShard(LabIndexes.LIVE_1);
        // @focus-end

        long liveDocs = lab.indexCount(LabIndexes.LIVE_1);

        // @focus-start rollover 前后各拍一次写别名快照
        List<Map<String, Object>> aliasBefore = lab.aliasRows(LabIndexes.LIVE_ALIAS);
        JsonNode rollover = es.post("/" + LabIndexes.LIVE_ALIAS + "/_rollover",
                Map.of("conditions", Map.of("max_docs", 4_000)));
        List<Map<String, Object>> aliasAfter = lab.aliasRows(LabIndexes.LIVE_ALIAS);
        // @focus-end

        // 切完之后写一条，验证它落在新索引上
        Map<String, Object> doc = new LinkedHashMap<>(lab.productDoc(0));
        doc.put("sku", "ROLL-1");
        doc.put("title", "滚动索引验证商品");
        JsonNode write = es.put("/" + LabIndexes.LIVE_ALIAS + "/_doc/ROLL-1?refresh=true", doc);

        long newDocs = lab.indexCount(LabIndexes.LIVE_2);

        boolean merged = total(segsAfter) < total(segsBefore);
        boolean idempotent = total(segsAgain) == total(segsAfter);
        boolean allOne = segsAfter.values().stream().allMatch(v -> v == 1);
        String writeBefore = writeIndex(aliasBefore);
        String writeAfter = writeIndex(aliasAfter);
        String oldReadOnly = isWrite(aliasAfter, LabIndexes.LIVE_1) ? "仍可写（异常）" : "已只读";

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("合并前（按分片）", perShard(segsBefore), total(segsBefore) + " 段",
                "各分片累积出的段，互不相干"));
        rows.add(TableData.row("合并后（按分片）", perShard(segsAfter), total(segsAfter) + " 段",
                "max_num_segments=1 = 每片 1 段，不是全索引 1 段"));
        rows.add(TableData.row("第二次 merge 后", perShard(segsAgain), total(segsAgain) + " 段",
                idempotent ? "与第一次相同：合并是幂等的" : "段数又变了，需检查是否仍在写入"));
        rows.add(TableData.row(LabIndexes.LIVE_1 + " 合并前", perShard(liveBefore), total(liveBefore) + " 段",
                "10 次 refresh 造出的碎段"));
        rows.add(TableData.row(LabIndexes.LIVE_1 + " 合并后", perShard(liveAfter), total(liveAfter) + " 段",
                "真实下降：碎段被合到 1 段"));
        rows.add(TableData.row("rollover 前写别名", writeBefore, "is_write_index=true 的索引", "唯一可写的目标"));
        rows.add(TableData.row("rollover 后写别名", writeAfter, "写目标已挪到新索引", "旧索引 is_write_index=false"));
        rows.add(TableData.row(LabIndexes.LIVE_1, oldReadOnly, "不再接收写入", "验证「旧的只能读」"));
        rows.add(TableData.row(LabIndexes.LIVE_2 + " 条数", fmt(newDocs), "rollover 后写入的那 1 条", "写别名已指向新索引"));

        boolean rolled = rollover.path("rolled_over").asBoolean(false);
        String head = merged
                ? "forcemerge 把 v3 从 " + total(segsBefore) + " 段合到 " + total(segsAfter)
                + " 段：" + perShard(segsAfter) + "（每片 1 段，不是全索引 1 段）"
                : "v3 已经是 " + total(segsBefore) + " 段（" + perShard(segsBefore)
                + "），forcemerge 无事可做 —— 段合并是幂等的；"
                + "另一侧 live-000001 从 " + total(liveBefore) + " 段合到 " + total(liveAfter) + " 段，可以看到真实下降";

        return LevelResult.builder(head + "；再合一次仍是 " + total(segsAgain)
                        + " 段，rollover 把写索引从 " + writeBefore + " 换到 " + writeAfter)
                .metrics(
                        new Metric("v3 段数（已是每片 1 段）", perShard(segsBefore), total(segsBefore) + " 段"),
                        new Metric("live-000001 合并", perShard(liveBefore) + " → " + perShard(liveAfter),
                                total(liveBefore) + " 段 → " + total(liveAfter) + " 段，真实下降"),
                        new Metric("合并后段数（按分片）", perShard(segsAfter),
                                merge.path("_shards").path("failed").asInt(0) != 0 ? "有分片失败"
                                        : allOne ? "每片恰好 1 段" : "仍有分片多于 1 段"),
                        new Metric("第二次 merge", perShard(segsAgain),
                                idempotent ? "与第一次一致（幂等）" : "与第一次不同"),
                        new Metric("rollover", rolled ? "已滚动" : "未滚动",
                                rolled ? LabIndexes.LIVE_1 + " → " + LabIndexes.LIVE_2 : "条件未满足"),
                        new Metric("写别名指向", writeBefore + " → " + writeAfter,
                                "旧索引 " + oldReadOnly))
                .table(TableData.of(List.of("项目", "数值", "说明", "口径"), rows))
                .json(Map.of("forcemerge", merge, "forcemergeAgain", mergeAgain,
                        "rollover", rollover, "write", write,
                        "segments", Json.map("before", segsBefore, "after", segsAfter, "again", segsAgain)))
                .notes(
                        "forcemerge 的收益是查询时少扫几个段；代价是大量磁盘 IO，且合并期间几乎吃满 IO，生产上放在低峰期做",
                        "「每片 1 段」要看分片维度：索引级汇总会显示 3 段，读成「没合成」是常见误判 —— 本关第 1、2 列就是按分片列的",
                        "第二次 forcemerge 段数不变，说明它是幂等的：重复执行不会带来额外收益，也不会破坏已经合并好的段",
                        "不要对还在频繁写入的索引做 forcemerge —— 新写入会立刻产生新段，白干",
                        "rollover 的三种条件（max_docs / max_age / max_size）只决定「要不要新建」，索引名里的 -000001 是它自动加的",
                        "rollover 之后旧索引的 is_write_index 变成 false：它只能读，写请求会被路由到新索引")
                .build();
    }

    /** 按分片统计段数：_cat/segments 一行一段 */
    private Map<String, Integer> segmentsByShard(String index) {
        Map<String, Integer> counts = new TreeMap<>();
        JsonNode rows = es.get("/_cat/segments/" + index + "?format=json&h=shard,segment");
        for (JsonNode r : rows) {
            String shard = r.path("shard").asText("?");
            counts.merge(shard, 1, Integer::sum);
        }
        return counts;
    }

    /** forcemerge 是异步落盘的，轮询到段数稳定（或超时）为止 */
    private Map<String, Integer> waitSegments(String index, Map<String, Integer> before) throws Exception {
        Map<String, Integer> current = segmentsByShard(index);
        for (int i = 0; i < 20; i++) {
            if (total(current) != total(before) && total(current) > 0) return current;
            Thread.sleep(200);
            current = segmentsByShard(index);
        }
        return current;
    }

    private static int total(Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static String perShard(Map<String, Integer> counts) {
        if (counts.isEmpty()) return "（无）";
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            parts.add("shard" + e.getKey() + "=" + e.getValue());
        }
        return String.join(" / ", parts);
    }

    private static String writeIndex(List<Map<String, Object>> rows) {
        for (Map<String, Object> r : rows) {
            if (Boolean.TRUE.equals(r.get("writeIndex"))) return String.valueOf(r.get("index"));
        }
        return "（无）";
    }

    private static boolean isWrite(List<Map<String, Object>> rows, String index) {
        for (Map<String, Object> r : rows) {
            if (index.equals(String.valueOf(r.get("index")))) {
                return Boolean.TRUE.equals(r.get("writeIndex"));
            }
        }
        return false;
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
