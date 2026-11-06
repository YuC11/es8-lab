package com.eslab.levels;

import com.eslab.levels.support.LabData;
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
 * m10-4：_delete_by_query 与写入幂等。
 *
 * 同一个请求跑两次，第二次应该删除 0 条 —— 这就是「幂等」在数据维护脚本里的意义。
 * 与之对照的是 PUT /_doc/{id}：默认覆盖，加 op_type=create 才变成「不存在才写」。
 */
@Component
public class M104DeleteByQuery implements LevelModule {

    private static final String OPS = LabData.OPS;

    private final EsClient es;
    private final LabData lab;

    public M104DeleteByQuery(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m10-4";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.resetOps();
        long before = lab.count(OPS);
        Object query = Json.map("query", Json.map("terms", Json.map("status", List.of(4, 5))));

        // @focus-start 下架(4)与清仓(5)一起清掉；refresh=true 让结果立刻可查
        JsonNode first = es.post("/" + OPS + "/_delete_by_query?conflicts=proceed&refresh=true", query);
        // @focus-end

        // 同样的请求再跑一次：应当删除 0 条
        JsonNode second = es.post("/" + OPS + "/_delete_by_query?conflicts=proceed&refresh=true", query);

        long after = lab.count(OPS);
        JsonNode exist = es.get("/" + OPS + "/_doc/ops-3");
        long versionBefore = exist.path("_version").asLong();

        // @focus-start op_type=create：已存在就 409，不覆盖
        String createResult;
        try {
            Map<String, Object> doc = new LinkedHashMap<>(lab.opsDoc(3));
            doc.put("stock", 777);
            es.put("/" + OPS + "/_doc/ops-3?op_type=create&refresh=true", doc);
            createResult = "成功（不符合预期）";
        } catch (EsException e) {
            createResult = e.status() + " " + e.payload().path("error").path("type").asText("error");
        }
        // @focus-end

        // 不带 op_type 就是覆盖写，版本号 +1
        Map<String, Object> overwrite = new LinkedHashMap<>(lab.opsDoc(3));
        overwrite.put("stock", 888);
        JsonNode put = es.put("/" + OPS + "/_doc/ops-3?refresh=true", overwrite);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("第一次 _delete_by_query",
                "扫描 " + first.path("total").asLong() + " · 删除 " + first.path("deleted").asLong(), "按 status=4/5 清理"));
        rows.add(TableData.row("第二次同样请求",
                "扫描 " + second.path("total").asLong() + " · 删除 " + second.path("deleted").asLong(), "已经删完，0 条"));
        rows.add(TableData.row("文档总数", before + " → " + after, "删掉的是 " + (before - after) + " 条"));
        rows.add(TableData.row("PUT ?op_type=create", createResult, "已存在 → 拒绝覆盖"));
        rows.add(TableData.row("PUT（默认）", put.path("result").asText() + " · _version=" + put.path("_version").asLong(),
                "覆盖写，版本 " + versionBefore + " → " + put.path("_version").asLong()));

        return LevelResult.builder("同一个删除请求跑两次：第一次删 " + first.path("deleted").asLong()
                        + " 条，第二次 0 条；而写入默认是覆盖，只有加 op_type=create 才会「已存在就拒绝」")
                .metrics(
                        new Metric("第一次删除", first.path("deleted").asLong() + " 条",
                                "扫描 " + first.path("total").asLong() + " 条"),
                        new Metric("第二次删除", second.path("deleted").asLong() + " 条", "幂等：已经没得删了"),
                        new Metric("文档数", before + " → " + after, "—"),
                        new Metric("op_type=create", createResult, "409 才是正确结果"),
                        new Metric("覆盖写版本", versionBefore + " → " + put.path("_version").asLong(), "默认行为"))
                .table(TableData.of(List.of("操作", "结果", "说明"), rows))
                .json(Map.of("first", first, "second", second, "put", put))
                .notes(
                        "_delete_by_query 是幂等的：条件不变，第二次自然删 0 条。这让它可以安全重跑",
                        "默认 PUT 是覆盖式写入，会悄悄抹掉别人的修改；同步任务应该用 op_type=create 或带 _seq_no",
                        "删除是「标记删除」，磁盘空间要到段合并后才真正释放 —— 这也是 m8-4 里 forcemerge 的意义之一")
                .build();
    }
}
