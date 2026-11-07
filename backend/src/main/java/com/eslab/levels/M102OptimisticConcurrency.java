package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * m10-2：乐观并发。
 *
 * ES 没有悲观锁。它给每条文档一个 (_seq_no, _primary_term)：
 * 你读到什么版本，就必须按这个版本写回去 —— 否则 409，由你决定重试还是放弃。
 */
@Component
public class M102OptimisticConcurrency implements LevelModule {

    private static final String OPS = LabIndexes.OPS;
    private static final String ID = "ops-2";

    private final EsClient es;
    private final LabData lab;

    public M102OptimisticConcurrency(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m10-2";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.resetOps();

        // @focus-start 读到的 _seq_no 与 _primary_term 就是这一版文档的「凭证」
        JsonNode doc = es.get("/" + OPS + "/_doc/" + ID);
        long seq = doc.path("_seq_no").asLong();
        long term = doc.path("_primary_term").asLong();
        int stock0 = doc.path("_source").path("stock").asInt();
        // @focus-end

        // @focus-start 带上凭证更新：成功者把数据推进到下一版
        JsonNode ok = es.post("/" + OPS + "/_update/" + ID
                        + "?if_seq_no=" + seq + "&if_primary_term=" + term,
                Json.map("doc", Json.map("stock", stock0 + 1)));
        long seqAfter = ok.path("_seq_no").asLong();
        // @focus-end

        // @focus-start 三种冲突处理方式，各自独立可见
        String stale = "?if_seq_no=" + seq + "&if_primary_term=" + term;
        String conflict1 = attempt(stale, 999);                              // ① 旧凭证：409
        String conflict2 = attempt(stale + "&retry_on_conflict=3", 999);      // ② 旧凭证 + retry：400（互斥）
        String retryOnly = attemptExpectSuccess("?retry_on_conflict=3", stock0 + 2); // ③ 只给 retry：成功
        // @focus-end

        // @focus-start 并发自增：参数可配置（-Deslab.m102.threads / -Deslab.m102.perThread），靠 retry_on_conflict 消化冲突
        int threads = Integer.getInteger("eslab.m102.threads", 4);
        int perThread = Integer.getInteger("eslab.m102.perThread", 25);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                for (int i = 0; i < perThread; i++) {
                    try {
                        es.post("/" + OPS + "/_update/" + ID + "?retry_on_conflict=10",
                                Json.map("script", Json.map("source", "ctx._source.stock += 1")));
                        success.incrementAndGet();
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get(120, TimeUnit.SECONDS);
        pool.shutdown();
        // @focus-end

        JsonNode after = es.get("/" + OPS + "/_doc/" + ID);
        int stock = after.path("_source").path("stock").asInt();
        long ver = after.path("_version").asLong();
        long seqFinal = after.path("_seq_no").asLong();
        int expected = stock0 + 2 + success.get();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("读取", "_seq_no=" + seq + " · _primary_term=" + term + " · stock=" + stock0,
                "拿到当前凭证（三个值一起才是完整起点）"));
        rows.add(TableData.row("带凭证更新", "_version → " + ok.path("_version").asLong(), "成功，_seq_no 变成 " + seqAfter));
        rows.add(TableData.row("用旧凭证再写", stale, conflict1));
        rows.add(TableData.row("旧凭证 + retry_on_conflict=3", "两个参数互斥", conflict2));
        rows.add(TableData.row("只给 retry_on_conflict=3（不带凭证）", "冲突自己重试", retryOnly));
        rows.add(TableData.row("并发 " + (threads * perThread) + " 次自增（" + threads + " 线程 × " + perThread + "）",
                success.get() + " 成功 / " + failed.get() + " 失败",
                "最终 stock=" + stock + "，期望 " + expected));

        return LevelResult.builder("起点 _seq_no=" + seq + "、stock=" + stock0 + "；用旧 _seq_no 写回被 409 拦住，"
                        + "再叠上 retry_on_conflict 时 ES 直接 400 —— 两个参数互斥；只给 retry_on_conflict 则成功。"
                        + "而 " + (threads * perThread) + " 次并发自增全部落地，库存从 " + stock0 + " 精确涨到 " + stock)
                .metrics(
                        new Metric("起点", "seq_no=" + seq + " · stock=" + stock0, "复现所需的最小信息"),
                        new Metric("_seq_no", seq + " → " + seqAfter, "每次写入推进一版"),
                        new Metric("旧凭证再写", conflict1, "这是并发安全的核心"),
                        new Metric("旧凭证 + retry", conflict2, "两个参数互斥"),
                        new Metric("只给 retry", retryOnly, "内部重试消化了冲突"),
                        new Metric("并发成功", success.get() + " / " + (threads * perThread),
                                failed.get() == 0 ? "无丢更新" : "有 " + failed.get() + " 次放弃"),
                        new Metric("最终库存", stock, "期望 " + expected + (stock == expected ? "（一致）" : "（不一致）")))
                .table(TableData.of(List.of("步骤", "条件", "结果"), rows))
                        .json(Json.map("seqNo", seq, "primaryTerm", term, "seqAfter", seqAfter,
                                "stock0", stock0, "stock", stock, "success", success.get(), "failed", failed.get(),
                                "threads", threads, "perThread", perThread, "version", ver, "seqFinal", seqFinal,
                                "conflict1", conflict1, "conflict2", conflict2, "retryOnly", retryOnly))
                .notes(
                        "if_seq_no 与 retry_on_conflict 不能同时用：前者要求「必须是这一版」，后者是「冲突就自己重试」，"
                                + "语义冲突，ES 直接返回 400 action_request_validation_exception",
                        "retry_on_conflict 只对内部版本冲突有效（每次重试都重新读最新版本再改），所以「只给 retry」能成功、并发自增也能全部落地",
                        "本关的线程数、每线程次数可通过 -Deslab.m102.threads / -Deslab.m102.perThread 改；改完核对最终库存是否仍等于「起点 + 2（前两步各写一次）+ 成功数」，默认参数下就是 " + expected,
                        "_version 与 _seq_no 不是一个量级：本关结束时 _version=" + ver + "（只数这一篇文档的修改），_seq_no=" + seqFinal + "（整个分片的写入序号，同分片其他关卡的写入也在推进它）",
                        "ES 不是库存主库：真实扣减要在数据库里做，再把结果同步过来；这里演示的是「同步时如何不覆盖别人的修改」")
                .build();
    }

    /** 预期成功的写入：失败才返回错误 */
    private String attemptExpectSuccess(String queryString, int stock) {
        try {
            JsonNode r = es.post("/" + OPS + "/_update/" + ID + queryString,
                    Json.map("doc", Json.map("stock", stock)));
            return "成功（result=" + r.path("result").asText("?") + "）";
        } catch (EsException e) {
            return "失败：" + e.status() + " " + (e.payload() == null ? "error"
                    : e.payload().path("error").path("type").asText("error"));
        }
    }

    private String attempt(String queryString, int stock) {
        try {
            es.post("/" + OPS + "/_update/" + ID + queryString,
                    Json.map("doc", Json.map("stock", stock)));
            return "成功（不符合预期）";
        } catch (EsException e) {
            String type = e.payload() == null ? "error"
                    : e.payload().path("error").path("type").asText("error");
            return e.status() + " " + type;
        }
    }
}
