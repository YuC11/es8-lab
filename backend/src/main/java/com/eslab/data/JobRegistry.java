package com.eslab.data;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 长任务（bulk 灌 2 万 / 20 万）的极简任务表。
 *
 * 为什么不直接让 HTTP 请求等到写完：
 *   20 万条要几十秒，前端既拿不到进度，也容易被浏览器/代理掐断。
 *   这里返回 jobId，前端 500ms 轮询一次，进度条画的是真实写入量，不是假动画。
 */
@Component
public class JobRegistry {

    public static final class Job {
        public final String id = UUID.randomUUID().toString().substring(0, 8);
        public final String kind;
        public final int total;
        public final Instant startedAt = Instant.now();

        public volatile String state = "RUNNING";
        public volatile int written;
        public volatile int rate;
        public volatile int batchIndex;
        public volatile int batches;
        public volatile long ms;
        public volatile Object result;
        public volatile String error;

        Job(String kind, int total) {
            this.kind = kind;
            this.total = total;
        }

        public void progress(DatasetService.BulkProgress p) {
            this.written = p.written();
            this.rate = p.rate();
            this.batchIndex = p.batchIndex();
            this.batches = p.batches();
            this.ms = java.time.Duration.between(startedAt, Instant.now()).toMillis();
        }

        public Map<String, Object> view() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("kind", kind);
            out.put("state", state);
            out.put("written", written);
            out.put("total", total);
            out.put("rate", rate);
            out.put("batchIndex", batchIndex);
            out.put("batches", batches);
            out.put("ms", ms);
            out.put("result", result);
            out.put("error", error);
            return out;
        }
    }

    @FunctionalInterface
    public interface Task {
        Object run(Job job) throws Exception;
    }

    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    /** 两个线程足够：同一时刻最多一个 bulk 在写，留一个给别的操作 */
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "eslab-job");
        t.setDaemon(true);
        return t;
    });

    public Job submit(String kind, int total, Task task) {
        Job job = new Job(kind, total);
        jobs.put(job.id, job);
        pool.submit(() -> {
            try {
                job.result = task.run(job);
                job.state = "DONE";
            } catch (Exception e) {
                job.error = String.valueOf(e.getMessage());
                job.state = "FAILED";
            } finally {
                job.ms = java.time.Duration.between(job.startedAt, Instant.now()).toMillis();
            }
        });
        return job;
    }

    public Job get(String id) {
        return jobs.get(id);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }
}
