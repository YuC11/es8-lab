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
import java.util.List;
import java.util.Map;

/**
 * m10-1：_update 到底更新了什么。
 *
 * 三种写法经常被混为一谈：doc（合并）、script（读改写）、index（整条替换）。
 * 这一关把它们放在同一条文档上依次执行，看 _version 与字段的变化。
 */
@Component
public class M101Update implements LevelModule {

    private static final String OPS = LabIndexes.OPS;

    private final EsClient es;
    private final LabData lab;

    public M101Update(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m10-1";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.resetOps();

        JsonNode before = es.get("/" + OPS + "/_doc/ops-1");
        int stock0 = before.path("_source").path("stock").asInt();

        // @focus-start doc 是「合并」：只改传进来的字段，其他字段原样保留
        JsonNode u1 = es.post("/" + OPS + "/_update/ops-1",
                Json.map("doc", Json.map("stock", 42)));
        // @focus-end

        // @focus-start script 是「读改写」：能引用已有值，也能同时改多个字段
        JsonNode u2 = es.post("/" + OPS + "/_update/ops-1", Json.map(
                "script", Json.map(
                        "source", "ctx._source.stock += params.d; ctx._source.version += 1",
                        "params", Json.map("d", -5))));
        // @focus-end

        // 写入与当前完全相同的值：ES 会检测出无变化
        JsonNode u3 = es.post("/" + OPS + "/_update/ops-1",
                Json.map("doc", Json.map("stock", 37)));

        // 文档不存在时按 upsert 处理
        JsonNode u4 = es.post("/" + OPS + "/_update/ops-99", Json.map(
                "doc", Json.map("sku", "OPS-99", "title", "upsert 出来的新商品", "stock", 1),
                "doc_as_upsert", true));

        JsonNode after = es.get("/" + OPS + "/_doc/ops-1");
        int stock = after.path("_source").path("stock").asInt();
        int innerVersion = after.path("_source").path("version").asInt();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("初始状态", "—", before.path("_version").asLong(), stock0, 1));
        rows.add(TableData.row("doc {stock:42}", u1.path("result").asText(), u1.path("_version").asLong(), 42, 1));
        rows.add(TableData.row("script stock-5", u2.path("result").asText(), u2.path("_version").asLong(), 37, 2));
        rows.add(TableData.row("再写一次 stock:37", u3.path("result").asText(), u3.path("_version").asLong(), 37, 2));
        rows.add(TableData.row("upsert ops-99", u4.path("result").asText(), u4.path("_version").asLong(), 1, 1));

        boolean sawNoop = "noop".equals(u3.path("result").asText());

        return LevelResult.builder("同一条商品改了三次：stock 从 " + stock0 + " 到 42 再到 37，_version 到 "
                        + after.path("_version").asLong()
                        + "；第三次写入相同的值，ES 直接返回 noop，版本号没动")
                .metrics(
                        new Metric("最终 stock", stock, "doc 改成 42，script 再减 5"),
                        new Metric("_version", after.path("_version").asLong(), "每次真实修改 +1"),
                        new Metric("noop 检测", sawNoop ? "已触发" : "未触发", "写入值与原值相同"),
                        new Metric("upsert 结果", u4.path("result").asText(), "文档不存在时新建"))
                .table(TableData.of(List.of("操作", "result", "_version", "stock", "doc.version"), rows))
                .json(Map.of("u1", u1, "u2", u2, "u3", u3, "u4", u4))
                .notes(
                        "doc 不是替换整条：没传的字段保持原值。真正整条替换是 PUT /_doc/{id}",
                        "script 每次都要读 _source、执行、写回，成本比 doc 高；能不用就不用",
                        "noop 说明 ES 在写入前比对了 _source —— 它省掉的是一次索引写入，不是一次网络请求")
                .build();
    }
}
