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
import java.util.Map;

/**
 * M0-2 建你的第一个索引
 *
 * 这一关只做一件事：用一条 PUT 把索引建出来，然后看清 settings 里那两个数字的含义。
 * 刻意不给 mappings —— 让学员先看见「不写它也能建成功」，第 1 章再回来算这笔账。
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

        // @focus-start 建索引：只声明分片与副本，mappings 一个字都不写
        if (!existed) {
            dataset.create("v1");
        }
        // @focus-end

        JsonNode settings = es.get("/" + DatasetService.V1 + "/_settings")
                .path(DatasetService.V1).path("settings").path("index");
        JsonNode shards = es.get("/_cat/shards/" + DatasetService.V1
                + "?format=json&h=index,shard,prirep,state,docs");

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : shards) {
            rows.add(TableData.row(
                    "shard " + s.path("shard").asText(),
                    s.path("prirep").asText().equals("p") ? "主分片" : "副本",
                    s.path("state").asText(),
                    s.path("docs").asText()));
        }

        long docs = dataset.count(DatasetService.V1);

        return LevelResult.builder(existed
                        ? "索引已经存在（可能是之前建过或 seed 预置），本关直接读取它的真实配置。"
                        : "建好了：PUT /eslab-products-v1 带着 1 个分片、0 个副本——注意你没有写 mappings，ES 也没有报错。")
                .metrics(
                        new Metric("分片数", settings.path("number_of_shards").asText("?"),
                                "建索引时定死，之后不能改，只能 reindex"),
                        new Metric("副本数", settings.path("number_of_replicas").asText("?"),
                                "单节点设 0，否则永远是 yellow"),
                        new Metric("refresh_interval", settings.path("refresh_interval").asText("?"),
                                "写入后多久能被搜到"),
                        new Metric("当前文档数", docs, "还没写入，所以是 0"))
                .table(new TableData(List.of("分片", "类型", "状态", "文档数"), rows))
                .json(Map.of("settings", settings))
                .notes(
                        "不写 mappings 也能建索引——这是最关键的一点：映射不是在创建时确定的，"
                                + "而是在「第一次写入某个字段」时被动态推断出来的。第 1 章会看到它猜错的样子。",
                        "number_of_shards 一旦创建就无法修改（只能 reindex 重建）。所以分片规划必须提前做，"
                                + "这也是 M7 那一关存在的原因。",
                        "settings 里还有一个 index.uuid 和 creation_date，它们是 ES 内部标识，改不得也不必管。",
                        existed ? "本关没有重建索引，避免覆盖已有数据。想从头体验，在数据工坊删掉 v1 再回来。"
                                : "下一步：往这个空索引里写第一条文档，然后立刻查它。")
                .build();
    }
}
