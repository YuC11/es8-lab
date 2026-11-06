package com.eslab.levels;

import com.eslab.data.DatasetService;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M0-1 集群里有什么
 *
 * 课程的起点：先让学员看清「现在什么都没有」。但要分三步看，否则会把三件事混成一件：
 *   ① 集群健康（_cluster/health）：yellow 很常见，通常是「副本没地方放」；
 *   ② 节点（_cat/nodes）：单节点？堆多大？插件装了没？
 *   ③ 本项目索引（_cat/indices/eslab-*）：真正属于这门课的资产有几个。
 *
 * 关键区分：集群 yellow ≠ 本项目有问题。这台机器上 articles / test_aggs 这些既有索引
 * 带 1 副本，单节点放不下，于是未分配分片常年存在；eslab-* 自己是 green。
 * 所以本关把「未分配分片是谁的、原因是什么」单独列出来，不把别人的债算到自己头上。
 */
@Component
public class M01ClusterTour implements LevelModule {

    private final EsClient es;
    private final DatasetService dataset;

    public M01ClusterTour(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m0-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 第一步：集群健康 —— 只看状态不够，还要知道未分配分片有几个
        JsonNode health = es.get("/_cluster/health");
        // @focus-end

        // @focus-start 第二步：节点清单。单节点 / 堆大小 / 剩余磁盘决定了后面所有实验的规模
        JsonNode nodes = es.get("/_cat/nodes?format=json&h=name,version,heap.percent,cpu,disk.avail");
        // @focus-end

        // @focus-start 第三步：只统计本项目的索引，别把别人的业务索引算进课程结论
        JsonNode indices = es.get("/_cat/indices/" + DatasetService.PREFIX
                + "*?format=json&h=index,health,docs.count,store.size,pri,rep");
        // @focus-end

        // 第四步：未分配分片到底是谁的、为什么 —— 这是 yellow 的唯一可信解释
        JsonNode allShards = es.get("/_cat/shards?format=json&h=index,shard,prirep,state,unassigned.reason");
        int eslabUnassigned = 0;
        Set<String> otherIndices = new LinkedHashSet<>();
        Set<String> reasons = new LinkedHashSet<>();
        List<Map<String, Object>> unassigned = new ArrayList<>();
        for (JsonNode s : allShards) {
            if ("STARTED".equals(s.path("state").asText())) continue;
            String index = s.path("index").asText();
            String reason = s.path("unassigned.reason").asText("");
            unassigned.add(Json.map("index", index, "shard", s.path("shard").asText(),
                    "prirep", s.path("prirep").asText(), "reason", reason));
            if (index.startsWith(DatasetService.PREFIX)) eslabUnassigned++;
            else otherIndices.add(index);
            if (!reason.isEmpty()) reasons.add(reason);
        }

        List<String> unhealthy = new ArrayList<>();
        for (JsonNode r : indices) {
            String h = r.path("health").asText();
            if (!"green".equals(h)) unhealthy.add(r.path("index").asText() + "（" + h + "）");
        }
        boolean allEslabGreen = unhealthy.isEmpty();

        boolean hasIk = false;
        JsonNode plugins = es.get("/_cat/plugins?format=json&h=component,version");
        for (JsonNode p : plugins) {
            if ("analysis-ik".equals(p.path("component").asText())) hasIk = true;
        }

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode r : indices) {
            rows.add(TableData.row(
                    r.path("index").asText(),
                    r.path("health").asText(),
                    r.path("docs.count").asText(),
                    r.path("pri").asText(),
                    r.path("rep").asText(),
                    r.path("store.size").asText()));
        }
        if (rows.isEmpty()) {
            // 空结果本身就是要展示的证据：不是表格坏了，是确实一个都没有
            rows.add(TableData.row("（空）", "—", "0", "—", "—", "—"));
        }

        String status = health.path("status").asText();
        int clusterUnassigned = health.path("unassigned_shards").asInt();
        String attribution = clusterUnassigned == 0
                ? "没有未分配分片"
                : "未分配分片 " + clusterUnassigned + " 个，其中 eslab-* 占 " + eslabUnassigned
                + " 个" + (otherIndices.isEmpty() ? "" : "，其余来自既有索引 " + String.join("、", otherIndices));

        String summary;
        if (indices.isEmpty()) {
            summary = "集群 " + status + "、" + nodes.size() + " 个节点，eslab-* 下 0 个索引 —— 这就是第 0 章的起点："
                    + "一个空库（" + attribution + "，所以 yellow 不是本项目的错）";
        } else {
            summary = "集群 " + status + "、" + nodes.size() + " 个节点，eslab-* 下有 " + indices.size()
                    + " 个索引" + (allEslabGreen ? "且全部 green"
                    : "，" + unhealthy.size() + " 个不是 green：" + String.join("、", unhealthy))
                    + "；" + attribution;
        }

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("health", health);
        json.put("nodes", nodes);
        json.put("eslabIndices", indices);
        json.put("unassigned", unassigned);
        json.put("unassignedReasons", reasons);

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("节点数", health.path("number_of_nodes").asInt(),
                                "单节点开发集群；副本无处分配的根因"),
                        new Metric("集群状态", status,
                                "green=全部分片可用；yellow=有副本未分配"),
                        new Metric("eslab 索引", indices.size(),
                                "只统计 eslab-* 前缀，不计既有业务索引"),
                        new Metric("未分配分片", clusterUnassigned,
                                "eslab-* " + eslabUnassigned + " 个"
                                        + (reasons.isEmpty() ? "" : " · 原因 " + String.join("/", reasons))),
                        new Metric("ik 分词插件", hasIk ? "已安装" : "未安装",
                                hasIk ? "第 1 章建 v2 可直接用" : "缺它 v2 的 ik_max_word 建不起来"))
                .table(new TableData(List.of("索引", "健康", "文档数", "主分片", "副本", "体积"), rows))
                .json(json)
                .notes(
                        "三步分开看：集群健康说「整体有没有分片放不下」，节点说「这台机器有多少资源」，"
                                + "索引清单说「这门课手上有什么」。混在一起看，就会把别人的 yellow 当成自己的问题。",
                        "单节点集群里，任何副本数=1 的索引都会留下未分配分片，集群状态卡在 yellow —— "
                                + "这不是故障，是「副本需要第二个节点」这条规则的正常结果。unassigned.reason 会写清楚原因"
                                + (reasons.isEmpty() ? "。" : "（本机是 " + String.join("/", reasons) + "）。"),
                        "本关只把 eslab-* 算进课程资产：既有索引（articles、test_aggs 等）与本课程无关，"
                                + "它们的分片状态不该出现在你的结论里。",
                        indices.isEmpty()
                                ? "表里那一行「（空）」就是空库的证据：0 个索引、0 条文档。下一步去 m0-2 亲手建第一个。"
                                : "已经有索引了：可能是之前跑过，也可能被 sh/seed.sh 快进过。想从零体验就先清空。")
                .build();
    }
}
