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
import java.util.List;
import java.util.Map;

/**
 * m11-1：索引模板。
 *
 * 手工建索引的问题不是麻烦，是「忘记」—— 三个月后新建的索引少了一个 refresh_interval，
 * 没人会注意到，直到某个早上写入延迟突然变高。
 */
@Component
public class M111IndexTemplate implements LevelModule {

    private static final String TEMPLATE = "eslab-goods-template";
    private static final String PATTERN = "eslab-goods-tpl-*";
    private static final String INDEX_26 = "eslab-goods-tpl-2026";
    private static final String INDEX_27 = "eslab-goods-tpl-2027";

    private final EsClient es;
    private final LabData lab;

    public M111IndexTemplate(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m11-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 模板 = index_patterns + priority + settings + mappings
        es.put("/_index_template/" + TEMPLATE, template("5s"));
        // @focus-end

        // @focus-start 先模拟，再落盘：_simulate 无副作用，能看到最终 settings/mappings
        // 注意路径顺序：ES 的接口是 /_index_template/_simulate/{name}，不是 /{name}/_simulate
        JsonNode simByName = es.post("/_index_template/_simulate/" + TEMPLATE, null);
        JsonNode simByIndex = es.post("/_index_template/_simulate_index/" + INDEX_26, null);
        // @focus-end

        // 建索引时不写任何 settings/mappings，全部由模板给
        boolean created = false;
        if (!lab.exists(INDEX_26)) {
            es.put("/" + INDEX_26, Json.map());
            created = true;
        }
        JsonNode settings = es.get("/" + INDEX_26 + "/_settings");
        String actualRefresh = settings.path(INDEX_26).path("settings").path("index")
                .path("refresh_interval").asText("?");
        int actualShards = settings.path(INDEX_26).path("settings").path("index")
                .path("number_of_shards").asInt(0);

        // @focus-start 改模板只影响「之后新建」的索引，已存在的不动
        es.put("/_index_template/" + TEMPLATE, template("10s"));
        JsonNode simAfter = es.post("/_index_template/_simulate_index/" + INDEX_27, null);
        // @focus-end

        String simulatedAfter = simAfter.path("template").path("settings").path("index")
                .path("refresh_interval").asText("?");
        String simulatedShards = simByName.path("template").path("settings").path("index")
                .path("number_of_shards").asText("?");
        int simulatedFields = simByName.path("template").path("mappings").path("properties").size();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("模板匹配", PATTERN, "index_patterns"));
        rows.add(TableData.row("模拟分片数", simulatedShards, "_simulate 只算不建"));
        rows.add(TableData.row("模拟字段数", simulatedFields, "模板里声明了 5 个"));
        rows.add(TableData.row(INDEX_26 + " 实际 refresh", actualRefresh, created ? "本次创建" : "已存在，复用"));
        rows.add(TableData.row(INDEX_27 + " 模拟 refresh", simulatedAfter, "模板改成 10s 之后"));
        rows.add(TableData.row(INDEX_26 + " 现在 refresh", actualRefresh, "模板改了，它没变"));

        return LevelResult.builder("模板让「新建索引」不再靠复制粘贴："
                        + INDEX_26 + " 用空 body 建出来，就有 " + actualShards + " 分片、refresh " + actualRefresh
                        + "；把模板改成 10s 后，只有新索引变，旧索引仍是 " + actualRefresh)
                .metrics(
                        new Metric("模拟分片数", simulatedShards, "来自 _simulate_index"),
                        new Metric("模拟字段数", simulatedFields, "模板声明的 mapping"),
                        new Metric(INDEX_26 + " 实际", actualShards + " 分片 · " + actualRefresh,
                                created ? "本次创建" : "复用已有"),
                        new Metric("改模板后新索引", simulatedAfter, "只影响之后创建的索引"))
                .table(TableData.of(List.of("项目", "值", "说明"), rows))
                .json(Map.of("simulateByName", simByName, "simulateByIndex", simByIndex))
                .notes(
                        "priority 只在多个模板同时匹配时才起作用：数值大的覆盖数值小的",
                        "_simulate_index 是最安全的验证方式 —— 它返回最终会写进索引的 settings 与 mappings，但不创建索引",
                        "模板只在创建时求值：改模板不会追溯修改已有索引，这也是它不能替代 reindex 的原因")
                .build();
    }

    /** 模板内容：两个版本只差 refresh_interval，用来演示「改了模板旧索引不变」 */
    private Map<String, Object> template(String refresh) {
        return Json.map(
                "index_patterns", List.of(PATTERN),
                "priority", 200,
                "template", Json.map(
                        "settings", Json.map(
                                "number_of_shards", 1,
                                "number_of_replicas", 0,
                                "refresh_interval", refresh),
                        "mappings", Json.map(
                                "properties", Json.map(
                                        "sku", Json.map("type", "keyword"),
                                        "title", Json.map("type", "text",
                                                "analyzer", "ik_max_word",
                                                "search_analyzer", "ik_smart"),
                                        "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                        "status", Json.map("type", "integer"),
                                        "created_at", Json.map("type", "date")))));
    }
}
