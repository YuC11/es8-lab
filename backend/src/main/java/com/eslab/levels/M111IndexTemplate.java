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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * m11-1：索引模板。
 *
 * 手工建索引的问题不是麻烦，是「忘记」—— 三个月后新建的索引少了一个 refresh_interval，
 * 没人会注意到，直到某个早上写入延迟突然变高。
 *
 * 本关不止「模拟」：真的用空 body 建两个索引，读回它们的 settings；
 * 再叠一个优先级更高的模板，看 _simulate 里谁赢；最后用一个不匹配的索引名做反例。
 * 结束前把索引与模板都删掉 —— 模板是全局状态，留着会影响别人的实验。
 */
@Component
public class M111IndexTemplate implements LevelModule {

    private static final String TEMPLATE = "eslab-goods-template";
    private static final String TEMPLATE_HI = "eslab-goods-template-hi";
    private static final String PATTERN = "eslab-goods-tpl-*";
    private static final String INDEX_26 = "eslab-goods-tpl-2026";
    private static final String INDEX_27 = "eslab-goods-tpl-2027";
    private static final String NON_MATCH = "eslab-not-match-probe";

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
        long simShards = 0;
        long simFields = 0;
        String simRefresh = "?";
        String simAfterHi = "?";
        String actual26 = "?";
        String actual27 = "?";
        int shards26 = 0;
        boolean nonMatchHasTemplate = false;
        boolean cleaned = false;
        Map<String, Object> json = new LinkedHashMap<>();

        try {
            // @focus-start 模板 = index_patterns + priority + settings + mappings
            es.put("/_index_template/" + TEMPLATE, template("5s", 200));
            // @focus-end

            // @focus-start 先模拟，再落盘：_simulate 无副作用，能看到最终 settings/mappings
            // 注意路径顺序：ES 的接口是 /_index_template/_simulate/{name}，不是 /{name}/_simulate
            JsonNode simByName = es.post("/_index_template/_simulate/" + TEMPLATE, null);
            JsonNode simByIndex = es.post("/_index_template/_simulate_index/" + INDEX_26, null);
            // @focus-end

            simShards = simByName.path("template").path("settings").path("index").path("number_of_shards").asLong(0);
            simFields = simByName.path("template").path("mappings").path("properties").size();
            simRefresh = simByName.path("template").path("settings").path("index")
                    .path("refresh_interval").asText("?");
            String simByIndexRefresh = simByIndex.path("template").path("settings").path("index")
                    .path("refresh_interval").asText("?");

            // @focus-start 真的建两个索引：空 body，settings/mappings 全部由模板给
            for (String name : List.of(INDEX_26, INDEX_27)) {
                if (lab.exists(name)) es.delete("/" + name);
                es.put("/" + name, Json.map());
            }
            JsonNode settings26 = es.get("/" + INDEX_26 + "/_settings");
            JsonNode settings27 = es.get("/" + INDEX_27 + "/_settings");
            actual26 = settings26.path(INDEX_26).path("settings").path("index")
                    .path("refresh_interval").asText("?");
            actual27 = settings27.path(INDEX_27).path("settings").path("index")
                    .path("refresh_interval").asText("?");
            shards26 = settings26.path(INDEX_26).path("settings").path("index")
                    .path("number_of_shards").asInt(0);
            // @focus-end

            // @focus-start 叠一个 priority 更高的模板：同一 pattern，refresh 30s，priority 300
            es.put("/_index_template/" + TEMPLATE_HI, template("30s", 300));
            JsonNode simHi = es.post("/_index_template/_simulate_index/" + INDEX_26, null);
            simAfterHi = simHi.path("template").path("settings").path("index")
                    .path("refresh_interval").asText("?");
            // @focus-end

            // @focus-start 反例：索引名不匹配 pattern，模板不参与
            JsonNode nonMatch = es.post("/_index_template/_simulate_index/" + NON_MATCH, null);
            nonMatchHasTemplate = nonMatch.path("template").path("settings").path("index")
                    .path("refresh_interval").isMissingNode();
            // @focus-end

            json.put("simulateByName", simByName);
            json.put("simulateByIndex", simByIndex);
            json.put("simulateByIndexRefresh", simByIndexRefresh);
        } finally {
            // @focus-start 模板与索引都是全局状态：本关自己收拾干净
            for (String tpl : List.of(TEMPLATE_HI, TEMPLATE)) {
                try {
                    es.delete("/_index_template/" + tpl);
                } catch (Exception ignored) {
                    // 不存在也算清理成功
                }
            }
            for (String index : List.of(INDEX_26, INDEX_27)) {
                try {
                    es.delete("/" + index);
                } catch (Exception ignored) {
                    // 已删或不存在
                }
            }
            cleaned = !lab.exists(INDEX_26) && !lab.exists(INDEX_27);
            // @focus-end
        }

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("模板匹配", PATTERN, "index_patterns"));
        rows.add(TableData.row("模拟分片数", String.valueOf(simShards), "_simulate 只算不建"));
        rows.add(TableData.row("模拟字段数", String.valueOf(simFields), "模板里声明了 5 个"));
        rows.add(TableData.row(INDEX_26 + " 实际", shards26 + " 分片 · " + actual26, "空 body 建出来就有模板的配置"));
        rows.add(TableData.row(INDEX_27 + " 实际", actual27, "第二个索引，同样空 body"));
        rows.add(TableData.row("叠 priority 300 后模拟", simAfterHi, "同 pattern 时高优先级胜出"));
        rows.add(TableData.row("不匹配的索引名模拟", nonMatchHasTemplate ? "没有模板生效" : "仍套用了模板（异常）",
                NON_MATCH + " 不匹配 " + PATTERN));
        rows.add(TableData.row("收尾", cleaned ? "模板与索引已删除" : "清理未完成", "模板是全局状态，必须自己收"));

        json.put("simRefresh", simRefresh);
        json.put("actual26", actual26);
        json.put("actual27", actual27);
        json.put("simAfterHi", simAfterHi);
        json.put("nonMatchHasTemplate", nonMatchHasTemplate);
        json.put("cleaned", cleaned);

        return LevelResult.builder("模板让「新建索引」不再靠复制粘贴：" + INDEX_26 + " 与 " + INDEX_27
                        + " 都用空 body 建出来，各自就有 " + shards26 + " 分片、refresh " + actual26
                        + "；再叠一个 priority 300 的模板后，模拟结果变成 " + simAfterHi
                        + "（高优先级胜出）；不匹配的索引名则不套用任何模板")
                .metrics(
                        new Metric("模拟分片数", String.valueOf(simShards), "来自 _simulate"),
                        new Metric("模拟字段数", String.valueOf(simFields), "模板声明的 mapping"),
                        new Metric("两个索引的实际 refresh", actual26 + " / " + actual27, "空 body 建出，全由模板决定"),
                        new Metric("priority 300 的模板", simAfterHi, "同 pattern 时高优先级覆盖"),
                        new Metric("不匹配反例", nonMatchHasTemplate ? "无模板" : "有模板（异常）", NON_MATCH),
                        new Metric("清理", cleaned ? "已删除" : "未完成", "模板 + 2 个索引"))
                .table(TableData.of(List.of("项目", "值", "说明"), rows))
                .json(json)
                .notes(
                        "priority 只在多个模板同时匹配时才起作用：数值大的覆盖数值小的；本关用 200 与 300 两个模板验证了一次",
                        "_simulate_index 是最安全的验证方式 —— 它返回最终会写进索引的 settings 与 mappings，但不创建索引",
                        "本关不再停在模拟：两个索引是真的用空 body 建出来的，读回的 refresh_interval 就是模板给的",
                        "索引名不匹配 pattern 时模板完全不参与 —— 这也是「模板没生效」最常见的原因（拼错 pattern）",
                        "模板只在创建时求值：改模板不会追溯修改已有索引，这也是它不能替代 reindex 的原因；"
                                + "本关结束会删掉模板与两个索引，不留全局副作用")
                .build();
    }

    /** 模板内容：priority 与 refresh 可配，用来演示覆盖与「改模板旧索引不变」 */
    private Map<String, Object> template(String refresh, int priority) {
        return Json.map(
                "index_patterns", List.of(PATTERN),
                "priority", priority,
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
