package com.eslab.levels;

import com.eslab.levels.support.LabData;
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

    private static final String OPS = LabData.OPS;
    private static final int TOTAL = LabData.OPS_DOCS;

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
        lab.recreate(OPS, lab.opsDefinition());
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < TOTAL; i++) {
            ids.add("P-" + i);
            docs.add(lab.productDoc(i));
        }
        lab.bulkWithIds(OPS, ids, docs);
        lab.refresh(OPS);
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

        int updated = 0, deleted = 0, created = 0, conflicted = 0, indexed = 0;
        for (JsonNode item : bulk.path("items")) {
            Iterator<Map.Entry<String, JsonNode>> f = item.fields();
            if (!f.hasNext()) continue;
            Map.Entry<String, JsonNode> e = f.next();
            int status = e.getValue().path("status").asInt(0);
            switch (e.getKey()) {
                case "update" -> updated++;
                case "delete" -> deleted++;
                case "create" -> {
                    if (status == 409) conflicted++;
                    else created++;
                }
                case "index" -> indexed++;
                default -> {
                }
            }
        }
        lab.refresh(OPS);
        long after = lab.count(OPS);

        // @focus-start 条件改写：扫描命中集，逐条执行脚本；conflicts=proceed 让个别冲突不中断整体
        JsonNode ubq = es.post("/" + OPS + "/_update_by_query?conflicts=proceed&refresh=true", Json.map(
                "query", Json.map("range", Json.map("price", Json.map("lt", 100))),
                "script", Json.map("source",
                        "ctx._source.price = Math.round(ctx._source.price * 1.1 * 100) / 100.0")));
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("index", indexed, 201, "新增文档"));
        rows.add(TableData.row("update", updated, 200, "部分更新"));
        rows.add(TableData.row("delete", deleted, 200, "删除文档"));
        rows.add(TableData.row("create（已存在）", conflicted, 409, "刻意制造冲突，不影响其他动作"));
        rows.add(TableData.row("文档总数", before + " → " + after, "—",
                "2000 − 30 删除 + 10 新增 = 1980"));

        return LevelResult.builder("一次 bulk 发了 95 个动作： 50 改价、30 删除、5 个重复创建被拒、10 个新增；"
                        + "errors=true 但其余 90 个动作照常生效，文档数从 " + before + " 变成 " + after)
                .metrics(
                        new Metric("bulk 动作", updated + deleted + conflicted + created + indexed, quirk(bulk)),
                        new Metric("update", updated, "200 OK"),
                        new Metric("delete", deleted, "200 OK"),
                        new Metric("create 冲突", conflicted, "409，已存在"),
                        new Metric("文档数", before + " → " + after, "删除 30、新增 10"),
                        new Metric("_update_by_query", ubq.path("updated").asLong() + " 条改写",
                                "扫描 " + ubq.path("total").asLong() + " 条"))
                .table(TableData.of(List.of("动作", "条数", "状态码", "说明"), rows))
                .json(Map.of("bulk", bulk, "updateByQuery", ubq))
                .notes(
                        "bulk 的 errors=true 不代表整批失败：每个动作独立返回状态码，要按 items 逐个判断",
                        "重复创建用 create 动作，它是幂等的 —— 已存在就 409，不会覆盖别人的修改",
                        "_update_by_query 是「扫描 + 逐条改」，几百万条会跑很久，且期间的新写入可能被覆盖；适合一次性修数据")
                .build();
    }

    private static String quirk(JsonNode bulk) {
        return bulk.path("errors").asBoolean(false) ? "errors=true，逐条看 items" : "errors=false";
    }
}
