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

    private static final String OPS = LabData.OPS;
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

        String stale = "?if_seq_no=" + seq + "&if_primary_term=" + term;
        String conflict1 = attempt(stale, 999);
        String conflict2 = attempt(stale + "&retry_on_conflict=3", 999);

        // @focus-start 并发自增：100 次写，4 个线程，靠 retry_on_conflict 内部重试消化冲突
        int threads = 4;
        int perThread = 25;
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
        int expected = stock0 + 1 + success.get();

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("读取", "_seq_no=" + seq + " · _primary_term=" + term, "拿到当前凭证"));
        rows.add(TableData.row("带凭证更新", "_version → " + ok.path("_version").asLong(), "成功，_seq_no 变成 " + seqAfter));
        rows.add(TableData.row("用旧凭证再写", stale, conflict1));
        rows.add(TableData.row("旧凭证 + retry_on_conflict=3", "两个参数互斥", conflict2));
        rows.add(TableData.row("并发 100 次自增", success.get() + " 成功 / " + failed.get() + " 失败",
                "最终 stock=" + stock + "，期望 " + expected));

        return LevelResult.builder("用旧 _seq_no 写回被 409 拦住；再叠上 retry_on_conflict 时 ES 直接 400 —— "
                        + "这两个参数互斥。而 100 次并发自增在 retry_on_conflict 下全部落地，库存从 " + stock0 + " 精确涨到 " + stock)
                .metrics(
                        new Metric("_seq_no", seq + " → " + seqAfter, "每次写入推进一版"),
                        new Metric("旧凭证再写", conflict1, "这是并发安全的核心"),
                        new Metric("并发成功", success.get() + " / 100",
                                failed.get() == 0 ? "无丢更新" : "有 " + failed.get() + " 次放弃"),
                        new Metric("最终库存", stock, "期望 " + expected + (stock == expected ? "（一致）" : "（不一致）")))
                .table(TableData.of(List.of("步骤", "条件", "结果"), rows))
                .json(Map.of("seqNo", seq, "primaryTerm", term, "seqAfter", seqAfter,
                        "stock", stock, "success", success.get(), "failed", failed.get()))
                .notes(
                        "if_seq_no 与 retry_on_conflict 不能同时用：前者要求「必须是这一版」，后者是「冲突就自己重试」，"
                                + "语义冲突，ES 直接返回 400 action_request_validation_exception",
                        "retry_on_conflict 只对内部版本冲突有效（每次重试都重新读最新版本再改），所以并发自增才能全部成功",
                        "_seq_no 是分片级的序号，不是文档版本：本关里 _version 才 2，_seq_no 已经是两千多 —— 因为同分片的其他写入也在推进它",
                        "ES 不是库存主库：真实扣减要在数据库里做，再把结果同步过来；这里演示的是「同步时如何不覆盖别人的修改」")
                .build();
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
