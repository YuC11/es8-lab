package com.eslab.levels;

import com.eslab.data.DatasetService;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
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
import java.util.Map;

/**
 * M0-2 建你的第一个索引
 *
 * 这一关回答两个「建之前必须先知道」的问题：
 *   ① 不写 settings，ES 会给你什么？—— 答案是 1 主分片 + 1 副本，单节点上副本无处分配，
 *      于是集群变 yellow，而且这个索引一建出来就不健康；
 *   ② 建完还能改吗？—— 主分片数不能原地改，本关会真的发一次 PUT 把 400 抓回来。
 *
 * 做法：先空 body 建一个探针索引看默认值（读完即删），再显式建 v1（1 分片 / 0 副本），
 * 两张 settings 并排，差异不需要靠文档背。
 */
@Component
public class M02CreateIndex implements LevelModule {

    private final EsClient es;
    private final DatasetService dataset;

    public M02CreateIndex(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m0-2";
    }

    @Override
    public LevelResult run() throws Exception {
        boolean existed = dataset.exists(DatasetService.V1);

        // @focus-start ① 默认值实物：空 body 建一个探针索引，只读它的 settings 与分片状态
        Map<String, Object> defaultSettings = Map.of();
        List<Map<String, Object>> defaultShards = new ArrayList<>();
        try {
            try {
                es.delete("/" + LabIndexes.DEMO_DEFAULTS);
            } catch (EsException ignored) {
                // 上一轮异常退出留下的残留，删掉重来
            }
            es.put("/" + LabIndexes.DEMO_DEFAULTS, Json.map());
            defaultSettings = asMap(es.get("/" + LabIndexes.DEMO_DEFAULTS
                    + "/_settings?include_defaults=true")
                    .path(LabIndexes.DEMO_DEFAULTS).path("settings").path("index"));
            for (JsonNode s : es.get("/_cat/shards/" + LabIndexes.DEMO_DEFAULTS
                    + "?format=json&h=shard,prirep,state,unassigned.reason")) {
                defaultShards.add(Json.map("shard", s.path("shard").asText(),
                        "prirep", s.path("prirep").asText(),
                        "state", s.path("state").asText(),
                        "reason", s.path("unassigned.reason").asText("")));
            }
        } finally {
            // 探针索引只在本次运行里有意义：异常路径也要删，否则会永远留在 yellow 里
            try {
                es.delete("/" + LabIndexes.DEMO_DEFAULTS);
            } catch (EsException ignored) {
                // 已经没了
            }
        }
        // @focus-end

        // @focus-start ② 显式建 v1：只声明分片与副本，mappings 一个字都不写
        if (!existed) {
            dataset.create("v1");
        }
        // @focus-end

        JsonNode settings = es.get("/" + DatasetService.V1 + "/_settings")
                .path(DatasetService.V1).path("settings").path("index");
        JsonNode mapping = es.get("/" + DatasetService.V1 + "/_mapping")
                .path(DatasetService.V1).path("mappings");
        JsonNode shards = es.get("/_cat/shards/" + DatasetService.V1
                + "?format=json&h=index,shard,prirep,state,docs");
        String health = es.get("/_cat/indices/" + DatasetService.V1
                + "?format=json&h=index,health").path(0).path("health").asText("?");

        // @focus-start ③ 建完就改主分片：把真实的 400 抓回来，而不是只写「不能改」
        String updateStatus = "未触发";
        String updateReason = "";
        try {
            es.put("/" + DatasetService.V1 + "/_settings",
                    Json.map("index", Json.map("number_of_shards", 2)));
        } catch (EsException e) {
            updateStatus = e.status() + " " + e.payload().path("error").path("type").asText("");
            updateReason = e.payload().path("error").path("reason").asText("");
        }
        // @focus-end

        long docs = dataset.count(DatasetService.V1);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("主分片", text(defaultSettings.get("number_of_shards")),
                settings.path("number_of_shards").asText("?")));
        rows.add(TableData.row("副本", text(defaultSettings.get("number_of_replicas")),
                settings.path("number_of_replicas").asText("?")));
        rows.add(TableData.row("refresh_interval", text(defaultSettings.get("refresh_interval")),
                settings.path("refresh_interval").asText("?")));
        rows.add(TableData.row("索引健康",
                defaultShards.stream().anyMatch(s -> "UNASSIGNED".equals(s.get("state"))) ? "yellow" : "green",
                health));
        rows.add(TableData.row("mapping 字段数", "0（空 body）",
                String.valueOf(mapping.path("properties").size())));
        String replicaState = "—";
        String replicaReason = "";
        for (Map<String, Object> s : defaultShards) {
            if ("r".equals(s.get("prirep"))) {
                replicaState = String.valueOf(s.get("state"));
                replicaReason = String.valueOf(s.get("reason"));
            }
        }
        rows.add(TableData.row("分片状态",
                "主分片 STARTED / 副本 " + replicaState + (replicaReason.isEmpty() ? "" : " · " + replicaReason),
                shards.size() + " 个分片全部 STARTED"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("defaultProbe", Json.map("settings", defaultSettings, "shards", defaultShards));
        json.put("settings", settings);
        json.put("mapping", mapping);
        json.put("updateShards", Json.map("result", updateStatus, "reason", updateReason));

        String summary = existed
                ? "索引已经存在，本关直接读它的真实配置：" + settings.path("number_of_shards").asText("?")
                + " 主分片 / " + settings.path("number_of_replicas").asText("?") + " 副本；"
                + "空 body 建出来的默认索引是 1 分片 1 副本，单节点上副本必然 UNASSIGNED"
                : "建好了：显式 1 主分片 / 0 副本，健康 " + health + "；而空 body 建出来默认是 1 分片 / 1 副本 —— "
                + "单节点上副本无处分配，索引一出生就 yellow。改主分片被 " + updateStatus + " 拒绝";

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("本次创建", existed ? "否（已存在）" : "是", existed ? "未覆盖已有数据" : "空索引"),
                        new Metric("主分片", settings.path("number_of_shards").asText("?"),
                                "建时定死；改它被 " + updateStatus + " 拒绝"),
                        new Metric("副本", settings.path("number_of_replicas").asText("?"),
                                "单节点必须 0，否则永远 yellow"),
                        new Metric("refresh_interval", settings.path("refresh_interval").asText("?"),
                                "写入后多久可被搜索"),
                        new Metric("默认建法", text(defaultSettings.get("number_of_shards")) + " 分片 / "
                                + text(defaultSettings.get("number_of_replicas")) + " 副本",
                                "空 body 的探针索引，读完即删"),
                        new Metric("当前文档数", docs, "v1 里已有的数据；本关不写文档"))
                .table(TableData.of(List.of("项目", "默认（空 body）", "本关显式建"), rows))
                .json(json)
                .notes(
                        "不写 mappings 也能建索引：映射不是创建时确定的，而是第一次写入某个字段时动态推断 —— "
                                + "第 1 章会看到它猜错的样子。",
                        "默认值不是「1 分片 0 副本」：ES 8 的默认是 1 主分片 + 1 副本。单节点上副本无法分配，"
                                + "所以新建索引若没显式写 replicas=0，索引会一直是 yellow（探针索引已经证明了这一点）。",
                        "number_of_shards 创建后不可修改，只能用 _reindex 建新索引再搬数据；"
                                + "报错原文：" + updateReason,
                        "本关的清理边界：探针索引 " + LabIndexes.DEMO_DEFAULTS + " 用完立即删除；"
                                + DatasetService.V1 + " 保留给后面几关用。要从零重来只删 v1 即可，不要动别的索引。",
                        existed ? "本关没有重建 v1，避免覆盖已有数据。想从头体验，删掉 v1 再回来。"
                                : "下一步：往这个空索引里写第一条文档，然后立刻读它 —— 你会看到「写进去」和「搜得到」是两件事。")
                .build();
    }

    private static Map<String, Object> asMap(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static String text(Object v) {
        return v == null ? "?" : String.valueOf(v);
    }
}
