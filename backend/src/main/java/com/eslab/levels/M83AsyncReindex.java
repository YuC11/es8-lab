package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.lib.EsClient;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
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
        // 源索引：v4 没有就从 v2 搬一份，保证本关可独立运行
        if (!lab.exists(LabData.V4) || lab.count(LabData.V4) < LabData.DOCS) {
            if (!lab.exists(LabData.V4)) {
                es.put("/" + LabData.V4, lab.v4Definition());
            }
            lab.reindex(LabData.SOURCE, LabData.V4, LabData.DOCS, null);
            lab.refresh(LabData.V4);
        }

        // @focus-start 每次从空索引开始，否则「已完成」会瞬间达成，看不到进度
        lab.recreate(LabData.V5, lab.v5Definition());
        // @focus-end

        // @focus-start wait_for_completion=false 立刻返回 task id，之后轮询 _tasks/{id}
        LabData.AsyncTask task = lab.reindexAsync(LabData.V4, LabData.V5, LabData.DOCS, 4_000);
        // @focus-end

        long docs = lab.count(LabData.V5);
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

        return LevelResult.builder("任务 " + task.taskId() + " 在 " + (task.elapsedMs() / 1000.0)
                        + " 秒内写完 " + fmt(task.created()) + " 条；期间 _tasks 返回的是真实增量，不是估算值")
                .metrics(
                        new Metric("任务 ID", task.taskId(), "每次运行都不同"),
                        new Metric("总耗时", String.format(Locale.ROOT, "%.1fs", task.elapsedMs() / 1000.0),
                                "限速 4000 条/秒"),
                        new Metric("最终写入", fmt(task.created()) + " 条", "目标 " + fmt(LabData.DOCS)),
                        new Metric("轮询次数", samples.size(),
                                sawRunning ? "观察到进行中状态" : "本次太快，未观察到中间态"),
                        new Metric("目标索引条数", fmt(docs), "reindex 完成并 refresh 后"))
                .table(TableData.of(List.of("时刻", "状态", "已写入", "批次"), rows))
                .chart(new BarChartData(bars, "条", "采样点的累计写入量：斜率就是实际吞吐"))
                .json(Map.of(
                        "taskId", task.taskId(),
                        "completed", task.completed(),
                        "created", task.created(),
                        "total", task.total(),
                        "batches", task.batches(),
                        "conflicts", task.conflicts()))
                .notes(
                        "wait_for_completion=false 让你拿到 task id 就走，HTTP 连接不会被 20 分钟的大重建占住",
                        "轮询看的是 ES 内部任务状态，不是客户端估算 —— 数字来自写入线程真实进度",
                        "限速只在教学里用；生产上通常不限速，靠 _tasks 监控进度、靠取消接口兜底")
                .build();
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
