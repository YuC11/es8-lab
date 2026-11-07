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
import java.util.Map;

/**
 * m10-1：_update 到底更新了什么。
 *
 * 三种写法经常被混为一谈：doc（合并）、script（读改写）、index（整条替换）。
 * 这一关把它们放在同一条文档上依次执行，**每一步之后都 GET 读回**，
 * 把 result、_version、_seq_no、_primary_term 四列并排看 —— 只读首尾两次是看不出
 * 「noop 时版本与 seq_no 都不动」的。
 */
@Component
public class M101Update implements LevelModule {

    private static final String OPS = LabIndexes.OPS;
    private static final String ID = "ops-1";

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

        // @focus-start 起点：把 _version / _seq_no / _primary_term 一起记下来
        JsonNode before = es.get("/" + OPS + "/_doc/" + ID);
        int stock0 = before.path("_source").path("stock").asInt();
        long version0 = before.path("_version").asLong();
        long seq0 = before.path("_seq_no").asLong();
        long term0 = before.path("_primary_term").asLong();
        // @focus-end

        // @focus-start doc 是「合并」：只改传进来的字段，其他字段原样保留
        JsonNode u1 = es.post("/" + OPS + "/_update/" + ID,
                Json.map("doc", Json.map("stock", 42)));
        JsonNode s1 = es.get("/" + OPS + "/_doc/" + ID);
        // @focus-end

        // @focus-start script 是「读改写」：能引用已有值，也能同时改多个字段
        JsonNode u2 = es.post("/" + OPS + "/_update/" + ID, Json.map(
                "script", Json.map(
                        "source", "ctx._source.stock += params.d; ctx._source.version += 1",
                        "params", Json.map("d", -5))));
        JsonNode s2 = es.get("/" + OPS + "/_doc/" + ID);
        // @focus-end

        // 写入与当前完全相同的值：ES 会检测出无变化
        JsonNode u3 = es.post("/" + OPS + "/_update/" + ID,
                Json.map("doc", Json.map("stock", 37)));
        JsonNode s3 = es.get("/" + OPS + "/_doc/" + ID);

        // 文档不存在时按 upsert 处理
        JsonNode u4 = es.post("/" + OPS + "/_update/ops-99", Json.map(
                "doc", Json.map("sku", "OPS-99", "title", "upsert 出来的新商品", "stock", 1),
                "doc_as_upsert", true));
        JsonNode s4 = es.get("/" + OPS + "/_doc/ops-99");

        // @focus-start 收尾：本关自己建的 upsert 文档自己删，不等下一次 resetOps
        boolean deleted = false;
        try {
            JsonNode d = es.delete("/" + OPS + "/_doc/ops-99?refresh=true");
            deleted = "deleted".equals(d.path("result").asText());
        } catch (Exception ignored) {
            // 已被删或不存在都不影响结论
        }
        // @focus-end

        int stock = s3.path("_source").path("stock").asInt();
        int innerVersion = s3.path("_source").path("version").asInt();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("初始状态", "—", version0, seq0, term0, stock0, 1));
        rows.add(TableData.row("doc {stock:42}", u1.path("result").asText(),
                s1.path("_version").asLong(), s1.path("_seq_no").asLong(),
                s1.path("_primary_term").asLong(), s1.path("_source").path("stock").asInt(),
                s1.path("_source").path("version").asInt()));
        rows.add(TableData.row("script stock-5", u2.path("result").asText(),
                s2.path("_version").asLong(), s2.path("_seq_no").asLong(),
                s2.path("_primary_term").asLong(), s2.path("_source").path("stock").asInt(),
                s2.path("_source").path("version").asInt()));
        rows.add(TableData.row("再写一次 stock:37", u3.path("result").asText(),
                s3.path("_version").asLong(), s3.path("_seq_no").asLong(),
                s3.path("_primary_term").asLong(), stock, innerVersion));
        rows.add(TableData.row("upsert ops-99", u4.path("result").asText(),
                s4.path("_version").asLong(), s4.path("_seq_no").asLong(),
                s4.path("_primary_term").asLong(), s4.path("_source").path("stock").asInt(),
                s4.path("_source").path("version").asInt()));

        boolean sawNoop = "noop".equals(u3.path("result").asText());
        boolean seqFrozen = s3.path("_seq_no").asLong() == s2.path("_seq_no").asLong();
        boolean seqAdvanced = s2.path("_seq_no").asLong() > s1.path("_seq_no").asLong();

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("u1", u1);
        json.put("u2", u2);
        json.put("u3", u3);
        json.put("u4", u4);
        json.put("states", Json.map(
                "before", Json.map("version", version0, "seqNo", seq0, "primaryTerm", term0),
                "afterDoc", Json.map("version", s1.path("_version").asLong(),
                        "seqNo", s1.path("_seq_no").asLong(), "primaryTerm", s1.path("_primary_term").asLong()),
                "afterScript", Json.map("version", s2.path("_version").asLong(),
                        "seqNo", s2.path("_seq_no").asLong(), "primaryTerm", s2.path("_primary_term").asLong()),
                "afterNoop", Json.map("version", s3.path("_version").asLong(),
                        "seqNo", s3.path("_seq_no").asLong(), "primaryTerm", s3.path("_primary_term").asLong())));
        json.put("deletedOps99", deleted);

        return LevelResult.builder("同一条商品改了三次：stock 从 " + stock0 + " 到 42 再到 37，_version 到 "
                        + s3.path("_version").asLong() + "、_seq_no 到 " + s3.path("_seq_no").asLong()
                        + "；第三次写入相同的值，ES 返回 noop —— 版本号与 seq_no 都" + (seqFrozen ? "没动" : "动了（异常）"))
                .metrics(
                        new Metric("最终 stock", stock, "doc 改成 42，script 再减 5"),
                        new Metric("_version", s3.path("_version").asLong(), "每次真实修改 +1"),
                        new Metric("_seq_no", s2.path("_seq_no").asLong() + " → " + s3.path("_seq_no").asLong(),
                                "真实写入 +1，noop 不加"),
                        new Metric("noop 检测", sawNoop ? "已触发" : "未触发",
                                "写入值与原值相同；seq_no " + (seqFrozen ? "未推进" : "推进了")),
                        new Metric("upsert 结果", u4.path("result").asText(), "文档不存在时新建"),
                        new Metric("ops-99 收尾", deleted ? "已删除" : "未删除", "本关自建自清"))
                .table(TableData.of(List.of("操作", "result", "_version", "_seq_no", "_primary_term",
                        "stock", "doc.version"), rows))
                .json(json)
                .notes(
                        "doc 不是替换整条：没传的字段保持原值。真正整条替换是 PUT /_doc/{id}",
                        "_seq_no 是分片级单调递增的写入序号，_primary_term 在主分片切换时 +1；乐观并发用的就是这两个值",
                        "noop 说明 ES 在写入前比对了 _source —— 它省掉的是一次索引写入，不是一次网络请求；所以 _version 与 _seq_no 都不动",
                        "script 每次都要读 _source、执行、写回，成本比 doc 高；能不用就不用",
                        "本关结束时删掉自己 upsert 出来的 ops-99，不留给下一次 resetOps 兜底")
                .build();
    }
}
