package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * m8-3：异步 reindex 与 _tasks 进度。
 *
 * 为什么要把 20k 条故意限速到 4000/s：本地不限速 1-2 秒就跑完，
 * 轮询只能看到「已完成」，这一关想教的「可以观察中间态」就演示不出来。
 *
 * 三条分支都跑一遍，因为「任务完成 ≠ 成功」：
 *   · 正常：created 20000、failures 0；
 *   · 失败：目标索引 strict 只允许 sku，每条都 mapping 失败，completed=true 但 failures 非空；
 *   · 超时：限速到跑不完，轮询到上限主动 _cancel，而不是继续等。
 */
@Component
public class M83AsyncReindex implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M83AsyncReindex(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-3";
    }

    @Override
    public LevelResult run() throws Exception {
        // 源索引：v4 没有或条数不符就整条重建，保证本关可独立运行
        lab.ensureByReindex(LabIndexes.V4);

        // @focus-start 分支一「正常」：每次从空索引开始，否则「已完成」会瞬间达成，看不到进度
        // maxDocs 传 0 = 不设上限：max_docs 与 slices 同时给会让 ES 搬不满（本机基线：2 万条只搬 16,667）
        lab.rebuild(LabIndexes.V5, idx -> 0);
        LabData.AsyncTask task = lab.reindexAsync(
                LabIndexes.V4, LabIndexes.V5, 0, 4_000, 30_000, 2);
        // @focus-end

        long docs = lab.count(LabIndexes.V5);

        // @focus-start 分支二「失败」：目标索引 strict 且只有 sku，v4 的文档一条都进不去
        LabData.AsyncTask failing;
        lab.rebuild(LabIndexes.REINDEX_FAIL, idx -> 0);
        try {
            failing = lab.reindexAsync(LabIndexes.V4, LabIndexes.REINDEX_FAIL, 100, -1, 15_000, 1);
        } finally {
            lab.delete(LabIndexes.REINDEX_FAIL);
        }
        // @focus-end

        // @focus-start 分支三「超时取消」：2000 条限速 100/s 要 20 秒，轮询只等 1.2 秒
        LabData.AsyncTask canceled;
        lab.rebuild(LabIndexes.V5_CANCEL, idx -> 0);
        try {
            canceled = lab.reindexAsync(LabIndexes.V4, LabIndexes.V5_CANCEL, 2_000, 100, 1_200, 1);
        } finally {
            Thread.sleep(300);
            lab.delete(LabIndexes.V5_CANCEL);
        }
        // @focus-end

        List<List<Object>> branchRows = new ArrayList<>();
        branchRows.add(TableData.row("正常", status(task), fmt(task.created()),
                task.anyFailure() ? String.valueOf(task.failures().size()) : "0",
                secs(task.elapsedMs()), "目标 v5，slices=2"));
        branchRows.add(TableData.row("失败（strict）", status(failing), fmt(failing.created()),
                String.valueOf(failing.failures().size()),
                secs(failing.elapsedMs()), cause(failing)));
        branchRows.add(TableData.row("超时取消", status(canceled), fmt(canceled.created()),
                String.valueOf(canceled.failures().size()),
                secs(canceled.elapsedMs()), canceled.canceled() ? "已调用 _tasks/{id}/_cancel" : "未发出取消"));

        List<LabData.Sample> samples = task.samples();
        Set<Integer> picks = new LinkedHashSet<>();
        int n = samples.size();
        if (n > 0) {
            picks.add(0);
            picks.add(n / 4);
            picks.add(n / 2);
            picks.add((3 * n) / 4);
            picks.add(n - 1);
        }

        List<List<Object>> rows = new ArrayList<>();
        List<BarChartData.Item> bars = new ArrayList<>();
        for (int i : picks) {
            if (i < 0 || i >= n) continue;
            LabData.Sample s = samples.get(i);
            rows.add(TableData.row(
                    String.format(Locale.ROOT, "%.1fs", s.atMs() / 1000.0),
                    s.completed() ? "已完成" : "进行中",
                    fmt(s.created()),
                    fmt(s.batches())));
            bars.add(new BarChartData.Item(
                    String.format(Locale.ROOT, "%.1fs", s.atMs() / 1000.0), s.created()));
        }

        boolean sawRunning = samples.stream().anyMatch(s -> !s.completed());

        return LevelResult.builder("正常分支 " + secs(task.elapsedMs()) + " 写完 " + fmt(task.created())
                        + " 条（failures 0）；失败分支 completed=" + failing.completed()
                        + " 但 failures " + failing.failures().size() + " 条 —— 完成不等于成功；"
                        + "超时分支轮询 " + secs(canceled.elapsedMs()) + " 后主动取消，取消前写入 "
                        + fmt(canceled.created()) + " 条")
                .metrics(
                        new Metric("任务 ID", task.taskId(), "每次运行都不同"),
                        new Metric("正常写入", fmt(task.created()) + " 条",
                                "限速 4000/s · slices=" + task.slices()),
                        new Metric("失败条数", failing.failures().size() + " 条",
                                failing.anyFailure() ? cause(failing) : "本次未复现失败"),
                        new Metric("超时取消", canceled.canceled() ? "已取消" : "未取消",
                                "取消前写入 " + fmt(canceled.created()) + " 条"),
                        new Metric("目标索引条数", fmt(docs), "reindex 完成并 refresh 后"))
                .table(TableData.of(List.of("时刻", "状态", "已写入", "批次"), rows))
                .chart(new BarChartData(bars, "条", "采样点的累计写入量：斜率就是实际吞吐"))
                .json(Json.map(
                        "taskId", task.taskId(),
                        "completed", task.completed(),
                        "created", task.created(),
                        "total", task.total(),
                        "batches", task.batches(),
                        "conflicts", task.conflicts(),
                        "slices", task.slices(),
                        "branches", branchRows,
                        "failures", task.failures(),
                        "failedTask", Json.map(
                                "completed", failing.completed(),
                                "created", failing.created(),
                                "failures", failing.failures().size(),
                                "sample", failing.failures().isEmpty() ? "" : failing.failures().get(0).toString()),
                        "canceledTask", Json.map(
                                "completed", canceled.completed(),
                                "timedOut", canceled.timedOut(),
                                "canceled", canceled.canceled(),
                                "created", canceled.created())))
                .notes(
                        "wait_for_completion=false 让你拿到 task id 就走，HTTP 连接不会被 20 分钟的大重建占住",
                        "轮询看的是 ES 内部任务状态，不是客户端估算 —— 数字来自写入线程真实进度",
                        "completed=true 只说明「任务结束了」，不说明「数据都进去了」：失败分支里 failures 才是结论；"
                                + "反过来，任务本身报错时 completed 可能是 false 而 error 非空",
                        "超时不是错误，是需要处理的状态：到了自己的等待上限就调 _tasks/{id}/_cancel，"
                                + "再看一眼取消前写进去多少 —— 生产中重建停不下来比失败更常见",
                        "slices 只在本机单节点做有限并行；真正的并行上限是分片数、磁盘与 CPU",
                        // 这一条是历史实测记录，不是本轮响应：必须写清条件，否则换数据量后就是错的
                        "本机基线（2 万条源、单节点，2026-11 记录）：max_docs 与 slices>1 同时给会搬不满 —— "
                                + "slices=2 只搬 16,667，slices=4 也是 16,667，slices=3 是 19,998。"
                                + "这组数字随源条数与分片数变化，不要当常数用；要精确条数就别传 max_docs，或把 slices 压到 1",
                        "限速只在教学里用；生产上通常不限速，靠 _tasks 监控进度、靠取消接口兜底")
                .build();
    }

    private static String status(LabData.AsyncTask t) {
        if (t.errored()) return "error";
        if (t.timedOut()) return t.canceled() ? "超时已取消" : "超时";
        if (t.completed()) return t.anyFailure() ? "完成（有失败）" : "完成";
        return "未完成";
    }

    private static String cause(LabData.AsyncTask t) {
        if (t.errored()) return t.error().path("type").asText("error");
        if (t.anyFailure()) {
            JsonNode f = t.failures().get(0);
            String type = f.path("cause").path("type").asText("");
            return type.isBlank() ? "见 json 里的 failures[0]" : type;
        }
        return "—";
    }

    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
