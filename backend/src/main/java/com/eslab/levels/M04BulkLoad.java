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
 * M0-4 把数据灌到 2 万条
 *
 * 前几关都是「一条一条」的手工操作，这一关换成真实做法：bulk 分批。
 * 三个数字会同时变化——写入速率、段数、体积——它们之间的拉扯就是后面 M3、M7 的主题。
 */
@Component
public class M04BulkLoad implements LevelModule {

    private static final int TARGET = DatasetService.DEFAULT_TARGET;

    private final EsClient es;
    private final DatasetService dataset;

    public M04BulkLoad(EsClient es, DatasetService dataset) {
        this.es = es;
        this.dataset = dataset;
    }

    @Override
    public String id() {
        return "m0-4";
    }

    @Override
    public LevelResult run() throws Exception {
        if (!dataset.exists(DatasetService.V1)) {
            throw new IllegalStateException("索引 " + DatasetService.V1 + " 还不存在，先完成 m0-2");
        }

        long before = dataset.count(DatasetService.V1);
        List<DatasetService.BulkProgress> samples = new ArrayList<>();

        DatasetService.BulkOutcome outcome;
        if (before >= TARGET) {
            // 已经够了就不重复写：重复执行必须幂等，否则多点两次就变成 4 万条
            Map<String, Object> st = dataset.describe(DatasetService.V1);
            outcome = new DatasetService.BulkOutcome(0, 0, 0, 0,
                    ((Number) st.getOrDefault("segments", 0)).longValue(),
                    ((Number) st.getOrDefault("storeBytes", 0)).longValue(),
                    before);
        } else {
            int startFrom = (int) before;
            int remaining = (int) (TARGET - before);
            outcome = dataset.bulkLoad(DatasetService.V1, remaining, startFrom, samples::add);
        }

        JsonNode stats = es.get("/" + DatasetService.V1 + "/_stats/segments");
        List<List<Object>> rows = new ArrayList<>();
        for (int i = 0; i < samples.size(); i++) {
            DatasetService.BulkProgress p = samples.get(i);
            if (i > 0 && i < samples.size() - 2) continue;
            rows.add(TableData.row("第 " + p.batchIndex() + " 批", p.written(), p.rate() + " docs/s"));
        }
        if (rows.isEmpty()) {
            rows.add(TableData.row("未写入", 0, "已有文档数已达到目标"));
        }

        double storeMb = outcome.storeBytes() / 1024.0 / 1024.0;

        return LevelResult.builder(before >= TARGET
                        ? "索引里已有 " + before + " 条，达到 2 万的目标，本关跳过写入（重复执行保持幂等）。"
                        : "2 万条写完了：" + String.format("%.1f 秒", outcome.ms() / 1000.0)
                        + "，平均 " + outcome.rate() + " docs/s，落成 " + outcome.segments() + " 个段。")
                .metrics(
                        new Metric("本次写入", outcome.written() + " 条", "起点 " + before + " 条"),
                        new Metric("总文档数", outcome.docs(), "以 _count 为准"),
                        new Metric("写入速率", outcome.rate() + " docs/s", "导入期 refresh_interval=-1"),
                        new Metric("段数", outcome.segments(), "每批刷新都会新增段，后台 merge 会合并"),
                        new Metric("体积", String.format("%.1f MB", storeMb), "store.size"))
                .table(new TableData(List.of("阶段", "累计写入", "速率"), rows))
                .json(Map.of(
                        "stats", stats.path("_all").path("primaries").path("segments"),
                        "outcome", Map.of("written", outcome.written(), "ms", outcome.ms(), "rate", outcome.rate())))
                .notes(
                        "导入期间把 refresh_interval 设成 -1，导完再恢复 1s：每次 refresh 都要生成新段并可能触发 merge，"
                                + "是批量导入最大的隐性开销。这就是本关第一件事。",
                        "每条文档的 _id 是自动生成的（bulk 只给了 _index）。M3-2 会对比「自定义 _id」与「自动 _id」的写入选路。",
                        "单节点 0 副本：如果开 1 副本，写入量翻倍且分片无法分配，集群会卡在 yellow。",
                        "现在 v1 里是 2 万条全动态映射的数据。下一章第一关就来算它的账：这些字段被猜成了什么类型。")
                .build();
    }
}
