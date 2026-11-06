package com.eslab.levels;

import com.eslab.data.DatasetService;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * M0-1 集群里有什么
 *
 * 课程的起点：先让学员看清「现在什么都没有」。
 * 后面每一关的数字都建立在这个空集群之上，所以第一步不是建索引，而是学会体检。
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
        // @focus-start 三件事：集群健康、节点、以及「我手上到底有几个索引」
        JsonNode health = es.get("/_cluster/health");
        JsonNode nodes = es.get("/_cat/nodes?format=json&h=name,version,heap.percent,cpu,disk.avail");
        JsonNode indices = es.get("/_cat/indices/" + DatasetService.PREFIX
                + "*?format=json&h=index,status,docs.count,store.size,pri,rep");
        // @focus-end

        boolean hasIk = false;
        JsonNode plugins = es.get("/_cat/plugins?format=json&h=component,version");
        for (JsonNode p : plugins) {
            if ("analysis-ik".equals(p.path("component").asText())) hasIk = true;
        }

        JsonNode node = nodes.path(0);
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode r : indices) {
            rows.add(TableData.row(
                    r.path("index").asText(),
                    r.path("status").asText(),
                    r.path("pri").asText() + " 分片",
                    r.path("docs.count").asText(),
                    r.path("store.size").asText()));
        }

        String summary = indices.isEmpty()
                ? "集群是空的：0 个 eslab-* 索引。这就是第 0 章的起点——接下来所有东西都由你亲手建。"
                : "集群里已经有 " + indices.size() + " 个实验索引。如果是你之前建的，可以继续；否则先在数据工坊清空重来。";

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("节点数", health.path("number_of_nodes").asInt(), "单节点开发集群"),
                        new Metric("集群状态", health.path("status").asText(),
                                "green=全部分片可用；yellow=副本未分配（单节点正常）"),
                        new Metric("eslab 索引", indices.size(), "只统计 eslab-* 前缀"),
                        new Metric("ik 分词插件", hasIk ? "已安装" : "未安装",
                                hasIk ? null : "缺它 v2 的 ik_max_word 会建不起来"))
                .table(new TableData(List.of("索引", "状态", "分片", "文档数", "体积"), rows))
                .json(java.util.Map.of("health", health, "nodes", nodes, "indices", indices))
                .notes(
                        "一个 index 在物理上是一组 shard；每个 shard 是一个 Lucene 实例，由若干不可变的 segment 组成。"
                                + "记住这三个词，后面 refresh、merge、深分页的代价都能从它们推出来。",
                        "单节点集群的副本数必须设 0，否则分片永远是 unassigned，集群状态卡在 yellow——这不是故障，是配置。",
                        hasIk ? "analysis-ik 已就绪，第 1 章建 v2 时可以直接用 ik_max_word。"
                                : "未检测到 analysis-ik：v2 的 ik_max_word 会报错，先装插件再继续。",
                        indices.isEmpty()
                                ? "现在去下一关：亲手创建第一个索引。"
                                : "已有索引说明数据被预置过（可能是 sh/seed.sh 快进）。想体验真实构建，可在数据工坊删掉重来。")
                .build();
    }
}
