package com.eslab.levels;

import com.eslab.data.DatasetService;
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
import java.util.Locale;
import java.util.Map;

/**
 * M0-3 写一条文档，然后立刻读回来
 *
 * 近实时（NRT）第一次现身：同一条文档，GET by _id 立刻拿得到，_search 却可能看不到。
 * 不是没写进去，是「读的道」和「搜的道」不是同一条。
 *
 * 为了让这个对照每次都成立，本关先清场：删掉旧的同 _id 文档并 refresh，
 * 保证「写入前一定搜不到、写入后一定还没刷新」。否则第二次运行会看到上一次刷新的结果，
 * 表格里就会出现「refresh 前后都是 1 条」，把结论变成「恰好赶上了」。
 *
 * 固定 _id 还带出另一个结论：重跑只更新同一条文档（version 递增），不会产生重复数据。
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

        // @focus-start ① 清场：删掉旧的同 _id 文档并 refresh —— 保证「写之前搜不到」是确定的
        long start = System.nanoTime();
        boolean cleaned = false;
        try {
            JsonNode del = es.delete("/" + index + "/_doc/" + DOC_ID + "?refresh=false");
            cleaned = "deleted".equals(del.path("result").asText());
        } catch (EsException e) {
            // 404：本来就没有这条，属于正常起点
        }
        es.post("/" + index + "/_refresh", null);
        long beforeWrite = countIds(index);
        double clearMs = elapsed(start);
        // @focus-end

        // @focus-start ② 写入一条：_id 由业务决定，这是幂等写入的前提
        long t0 = System.nanoTime();
        JsonNode write = es.put("/" + index + "/_doc/" + DOC_ID + "?refresh=false", Json.map(
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
        double writeMs = elapsed(t0);
        // @focus-end

        // @focus-start ③ 三条读路径：GET 实时、_search 只看得见已刷新的段
        long t1 = System.nanoTime();
        JsonNode got = es.get("/" + index + "/_doc/" + DOC_ID);
        double getMs = elapsed(t1);

        long t2 = System.nanoTime();
        long afterWrite = countIds(index);
        double searchMs = elapsed(t2);

        long t3 = System.nanoTime();
        JsonNode refreshed = es.post("/" + index + "/_refresh", null);
        double refreshMs = elapsed(t3);

        long t4 = System.nanoTime();
        long afterRefresh = countIds(index);
        double search2Ms = elapsed(t4);
        // @focus-end

        boolean found = got.path("found").asBoolean(false);
        long version = write.path("_version").asLong();
        long seqNo = write.path("_seq_no").asLong();
        String result = write.path("result").asText("?");

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("① 清理旧文档 + refresh", fmt(clearMs) + " ms",
                cleaned ? "删除了上一轮的文档" : "本来就没有（首次运行）",
                "该 _id 命中 " + beforeWrite + " 条，起点干净"));
        rows.add(TableData.row("② PUT 写入固定 _id", fmt(writeMs) + " ms",
                "result=" + result + " · _version=" + version + " · _seq_no=" + seqNo,
                "写 translog + 内存 buffer，不立即建段"));
        rows.add(TableData.row("③ GET /_doc/" + DOC_ID, fmt(getMs) + " ms",
                found ? "命中（_version=" + got.path("_version").asLong() + "）" : "未命中",
                "按 _id 直读，实时路径"));
        rows.add(TableData.row("④ 立即 _search（ids 查询）", fmt(searchMs) + " ms",
                afterWrite + " 条", "搜索只看得见已刷新的段"));
        rows.add(TableData.row("⑤ POST /_refresh", fmt(refreshMs) + " ms",
                refreshed.path("_shards").path("successful").asInt(0) + " 个分片刷新生效",
                "buffer 落成新的可搜索 segment"));
        rows.add(TableData.row("⑥ 再 _search（同一条查询）", fmt(search2Ms) + " ms",
                afterRefresh + " 条", "多出来的就是刚写的那条"));

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("write", write);
        json.put("get", got);
        json.put("counts", Json.map("beforeWrite", beforeWrite, "afterWrite", afterWrite,
                "afterRefresh", afterRefresh));
        json.put("refresh", refreshed);

        String summary;
        if (afterWrite == 0 && afterRefresh == 1) {
            summary = "写入后立刻验证：GET 命中（_version=" + version + "），_search 却是 0 条；"
                    + "refresh 之后同一条查询变 1 条 —— 搜索走段，GET 走 translog，两条路的可见性不一样";
        } else if (afterWrite == 1 && afterRefresh == 1) {
            summary = "这次 _search 立即看到了文档（写入与查询之间跨过了刷新窗口或段已合并），"
                    + "但 GET 与 _search 走的是两条路径这件事不变；重复运行本关会重新清场再测";
        } else {
            summary = "写入后 _search 命中 " + afterWrite + " 条、refresh 后 " + afterRefresh
                    + " 条 —— 与预期不符，先检查 refresh_interval 是否被改成 -1";
        }

        return LevelResult.builder(summary)
                .metrics(
                        new Metric("GET 读回", found ? "命中" : "未命中",
                                "实时路径 · _version=" + got.path("_version").asLong()),
                        new Metric("写入后 _search", afterWrite + " 条", "写进 buffer，还没建段"),
                        new Metric("refresh 后 _search", afterRefresh + " 条", "与上一条的差就是可见性延迟"),
                        new Metric("该 _id 文档数", afterRefresh + " 条", "固定 _id ⇒ 重跑不产生重复"),
                        new Metric("写入结果", result, "_version=" + version + " · _seq_no=" + seqNo),
                        new Metric("写入耗时", fmt(writeMs) + " ms", "单条 · refresh=false"))
                .table(TableData.of(List.of("步骤", "相对耗时", "结果", "为什么"), rows))
                .json(json)
                .notes(
                        "写入不是「直接落盘」：先写 translog（保证不丢），再进内存 buffer；refresh 时 buffer 落成"
                                + "新的不可变 segment，这时才可被搜索。所以「写完立刻搜不到」不是 bug，是设计。",
                        "GET by _id 走实时路径（会穿透到 translog），_search 只认已刷新的段 —— "
                                + "排查问题时这个差异极容易骗人：监控里「查不到」不代表「没写进去」。",
                        "本关先删旧文档再写，是为了让每次运行都从「搜不到」开始；否则上一轮的段还在，"
                                + "表格里 refresh 前后都是 1 条，看起来像「刷新没用」。这个清场动作本身就是可复现性的一部分。",
                        "固定 _id 带来幂等：重复运行只有 _version 递增，文档数始终是 1。"
                                + "换成自动 _id，每跑一次就多一条 —— 这是 M3-2 的主题。",
                        "本关的清理范围只有这一条文档：" + DOC_ID + "；不删索引、不动其他数据。")
                .build();
    }

    /** 用 ids 查询精确数这条 _id 的文档数：不受索引里已有 2 万条数据影响 */
    private long countIds(String index) {
        JsonNode res = es.post("/" + index + "/_count",
                Json.map("query", Json.map("ids", Json.map("values", Json.list(DOC_ID)))));
        return res.path("count").asLong(0);
    }

    private static double elapsed(long startedNanos) {
        return Math.round((System.nanoTime() - startedNanos) / 1_000_000.0 * 10) / 10.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
