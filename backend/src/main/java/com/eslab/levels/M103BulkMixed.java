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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * m10-3：bulk 的四种动作与 _update_by_query。
 *
 * 增量同步的真实形状不是「一条条 index」，而是一次 bulk 里混着
 * index / update / delete / create —— 而且部分失败是常态。
 */
@Component
public class M103BulkMixed implements LevelModule {

    private static final String OPS = LabIndexes.OPS;
    private static final int TOTAL = LabIndexes.OPS_DOCS;

    private final EsClient es;
    private final LabData lab;

    public M103BulkMixed(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m10-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // 重建 + 灌 2000 条 P-* 在同一个锁内完成，避免与 m10-1/10-2/10-4 并发时互相清空
        lab.rebuild(OPS, idx -> {
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < TOTAL; i++) {
                ids.add("P-" + i);
                docs.add(lab.productDoc(i));
            }
            return lab.bulkWithIds(idx, ids, docs);
        });
        long before = lab.count(OPS);

        // @focus-start 一次 bulk 里混四种动作：50 改价 / 30 删除 / 5 重复创建 / 10 新增
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            sb.append("{\"update\":{\"_index\":\"").append(OPS).append("\",\"_id\":\"P-").append(i).append("\"}}\n");
            sb.append("{\"doc\":{\"price\":999}}\n");
        }
        for (int i = 50; i < 80; i++) {
            sb.append("{\"delete\":{\"_index\":\"").append(OPS).append("\",\"_id\":\"P-").append(i).append("\"}}\n");
        }
        for (int i = 80; i < 85; i++) {
            sb.append("{\"create\":{\"_index\":\"").append(OPS).append("\",\"_id\":\"P-").append(i).append("\"}}\n");
            sb.append("{\"sku\":\"P-").append(i).append("\",\"title\":\"重复创建会失败\",\"price\":1}\n");
        }
        for (int i = 0; i < 10; i++) {
            sb.append("{\"index\":{\"_index\":\"").append(OPS).append("\",\"_id\":\"NEW-").append(i).append("\"}}\n");
            sb.append("{\"sku\":\"NEW-").append(i).append("\",\"title\":\"批量新增商品 ").append(i)
                    .append("\",\"price\":123.45,\"status\":1}\n");
        }
        JsonNode bulk = lab.bulkRaw(sb.toString());
        // @focus-end

        // 逐项读状态码：不能按「动作类型」猜结果 —— 同一个 update 也可能 409
        List<ItemStat> stats = new ArrayList<>();
        for (JsonNode item : bulk.path("items")) {
            Iterator<Map.Entry<String, JsonNode>> f = item.fields();
            if (!f.hasNext()) continue;
            Map.Entry<String, JsonNode> e = f.next();
            JsonNode n = e.getValue();
            stats.add(new ItemStat(e.getKey(), n.path("status").asInt(0),
                    n.path("_id").asText(""),
                    n.path("result").asText(n.path("error").path("type").asText(""))));
        }

        long ok = stats.stream().filter(ItemStat::ok).count();
        long conflicts = stats.stream().filter(x -> x.status() == 409).count();
        long failed = stats.size() - ok;

        long deletedOk = stats.stream().filter(x -> x.ok() && x.action().equals("delete")).count();
        long indexedOk = stats.stream().filter(x -> x.ok() && x.action().equals("index")).count();
        long createdOk = stats.stream().filter(x -> x.ok() && x.action().equals("create")).count();

        lab.refresh(OPS);
        long after = lab.count(OPS);
        long expected = before - deletedOk + indexedOk + createdOk;
        if (after != expected) {
            throw new IllegalStateException("bulk 计数对不上：before=" + before + " after=" + after
                    + " 期望=" + expected + "（delete 成功 " + deletedOk + " / index 成功 " + indexedOk
                    + " / create 成功 " + createdOk + "）");
        }

        // @focus-start 条件改写：扫描命中集，逐条执行脚本；conflicts=proceed 让个别冲突不中断整体
        JsonNode ubq = es.post("/" + OPS + "/_update_by_query?conflicts=proceed&refresh=true", Json.map(
                "query", Json.map("range", Json.map("price", Json.map("lt", 100))),
                "script", Json.map("source",
                        "ctx._source.price = Math.round(ctx._source.price * 1.1 * 100) / 100.0")));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<ItemStat>> e : group(stats).entrySet()) {
            List<ItemStat> group = e.getValue();
            String codes = group.stream().map(x -> String.valueOf(x.status())).distinct()
                    .reduce((a, b) -> a + " / " + b).orElse("-");
            String result = group.stream().map(ItemStat::result).filter(r -> !r.isBlank())
                    .distinct().reduce((a, b) -> a + " / " + b).orElse("-");
            rows.add(TableData.row(e.getKey(), group.size(), codes, result,
                    group.get(0).id()));
        }
        rows.add(TableData.row("合计", stats.size(), "—",
                "成功 " + ok + " / 冲突 " + conflicts, "—"));
        rows.add(TableData.row("文档总数", before + " → " + after, "—",
                before + " − " + deletedOk + " 删除 + " + indexedOk + " 新增 = " + expected));

        return LevelResult.builder("一次 bulk 发了 " + stats.size() + " 个动作："
                        + "50 改价、30 删除、5 个重复创建被拒、10 个新增；"
                        + "整体 HTTP 200、errors=" + bulk.path("errors").asBoolean(false)
                        + "，实际成功 " + ok + " 条、冲突 " + conflicts + " 条，文档数从 "
                        + before + " 变成 " + after)
                .metrics(
                        new Metric("bulk 动作", stats.size(), "逐项统计，不做类型推断"),
                        new Metric("成功 / 冲突", ok + " / " + conflicts, "按 items[].status 判定"),
                        new Metric("失败明细", failed + " 条",
                                failed == 0 ? "无" : "见表格的错误类型"),
                        new Metric("文档数", before + " → " + after,
                                "delete 成功 " + deletedOk + " · index 成功 " + indexedOk),
                        new Metric("_update_by_query", ubq.path("updated").asLong() + " 条改写",
                                "扫描 " + ubq.path("total").asLong()
                                        + " · 冲突 " + ubq.path("version_conflicts").asLong()
                                        + " · 失败 " + ubq.path("failures").size()))
                .table(TableData.of(List.of("动作", "条数", "状态码", "结果", "样例 _id"), rows))
                .json(Map.of("bulk", bulk, "updateByQuery", ubq,
                        "items", stats.stream().map(ItemStat::toString).toList()))
                .notes(
                        "bulk 的 errors=true 不代表整批失败：每个动作独立返回状态码，要按 items 逐个判断",
                        "表格里的状态码来自 items[].status，不是按动作类型写死的 —— 同一个 update 遇到版本冲突也会是 409",
                        "文档数的等式必须成立：before − delete 成功 + index/create 成功 = after。对不上说明计数逻辑在骗人",
                        "重复创建用 create 动作，它是幂等的 —— 已存在就 409，不会覆盖别人的修改",
                        "_update_by_query 是「扫描 + 逐条改」，几百万条会跑很久，且期间的新写入可能被覆盖；适合一次性修数据")
                .build();
    }

    private record ItemStat(String action, int status, String id, String result) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    /** 按动作分组，保持出现顺序，表格才和请求顺序对得上 */
    private static Map<String, List<ItemStat>> group(List<ItemStat> stats) {
        Map<String, List<ItemStat>> out = new java.util.LinkedHashMap<>();
        for (ItemStat s : stats) {
            out.computeIfAbsent(s.action(), k -> new ArrayList<>()).add(s);
        }
        return out;
    }
}
