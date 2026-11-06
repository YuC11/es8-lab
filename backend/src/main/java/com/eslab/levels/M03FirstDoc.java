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
import java.util.List;
import java.util.Map;

/**
 * M0-3 写一条文档，然后立刻读回来
 *
 * 近实时（NRT）这个概念的第一次现身，就藏在这一关：
 * 同一条文档，GET by _id 立刻能拿到，_search 却可能查不到。
 * 不是数据没写进去，是「搜的道」和「读的道」不是同一条。
 */
@Component
public class M03FirstDoc implements LevelModule {

    private static final String DOC_ID = "SKU2026000001";

    private final EsClient es;
    private final DatasetService dataset;

    public M03FirstDoc(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m0-3";
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(DatasetService.V1)) {
            throw new IllegalStateException("索引 " + DatasetService.V1 + " 还不存在，先完成 m0-2");
        }
        String index = DatasetService.V1;

        // @focus-start 写入一条：_id 由业务决定，这是幂等写入的前提
        long t0 = System.nanoTime();
        JsonNode write = es.put("/" + index + "/_doc/" + DOC_ID, Json.map(
                "sku", DOC_ID,
                "title", "声阔 主动降噪头戴耳机 Q45",
                "description", "轻量 旗舰 长续航 主动降噪 双设备连接",
                "category", "数码影音",
                "brand", "声阔",
                "price", 899.0,
                "stock", 120,
                "status", 1,
                "tags", Json.list("数码", "音频", "降噪"),
                "created_at", "2026-10-01T10:00:00+08:00",
                "rating", 4.7,
                "sales", 1820,
                "seller_id", "S0001",
                "specs", Json.map("color", "曜石黑", "material", "ABS", "weight_g", 245)));
        double writeMs = (System.nanoTime() - t0) / 1_000_000.0;
        // @focus-end

        // @focus-start 两条读路径：GET 是实时的，_search 只看得见已刷新的段
        JsonNode got = es.get("/" + index + "/_doc/" + DOC_ID);
        long beforeRefresh = searchCount(index);
        es.post("/" + index + "/_refresh", null);
        long afterRefresh = searchCount(index);
        // @focus-end

        boolean found = got.path("found").asBoolean(false);

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("PUT /_doc/" + DOC_ID, write.path("result").asText("created"),
                "写入成功，此时数据在 translog + 内存 buffer 里"));
        rows.add(TableData.row("GET /_doc/" + DOC_ID, found ? "命中" : "未命中",
                "GET 按 _id 直读，是实时的，能穿透到 translog"));
        rows.add(TableData.row("_search（refresh 前）", beforeRefresh + " 条",
                beforeRefresh > 0 && beforeRefresh == afterRefresh ? "这次刚好跨过了 1s 刷新窗口" : "看不到刚写的那条"));
        rows.add(TableData.row("POST /_refresh", "生成新段", "把内存 buffer 落成一个可搜索的 segment"));
        rows.add(TableData.row("_search（refresh 后）", afterRefresh + " 条", "现在能搜到了"));

        String summary = afterRefresh > beforeRefresh
                ? "同一条文档：GET 立刻读得到，_search 要等一次 refresh 才看得见——这就是「近实时」的全部含义。"
                : beforeRefresh == afterRefresh
                ? "这次 _search 恰好赶在刷新之后，两次计数相同。刷新窗口只有 1 秒，重复执行本关就能抓到差异。"
                : "写入后搜到了，说明刷新恰好发生在两次查询之间。";

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("写入耗时", String.format("%.1f ms", writeMs), "单条写入"),
                        new Metric("GET 读回", found ? "命中" : "未命中", "实时路径"),
                        new Metric("refresh 前 _search", beforeRefresh + " 条", "只统计已刷新的段"),
                        new Metric("refresh 后 _search", afterRefresh + " 条", "多出来的就是刚写的那条"))
                .table(new TableData(List.of("操作", "结果", "为什么"), rows))
                .json(Map.of("write", write, "get", got))
                .notes(
                        "写入不是「直接落到磁盘」：先写 translog（保证不丢），再进内存 buffer，"
                                + "refresh 时 buffer 被写成一个新的不可变 segment，这时才可被搜索。",
                        "refresh 默认 1s。所以「写完立刻查不到」不是 bug，是设计：用 1 秒的可见性延迟换取吞吐。",
                        "GET by _id 走的是实时的 translog 读取，所以它和 _search 的可见性不一致——排查问题时这个差异会骗人。",
                        "数据量上来后（下一关），你会看到 refresh 的另一种代价：段太多会拖慢搜索并触发后台 merge。")
                .build();
    }

    private long searchCount(String index) {
        JsonNode res = es.post("/" + index + "/_search",
                Json.map("size", 0, "query", Json.map("match_all", Json.map())));
        return res.path("hits").path("total").path("value").asLong(0);
    }
}
