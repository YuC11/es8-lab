package com.eslab.data;

import com.eslab.lib.ProjectRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据工坊接口：让「建索引 / 灌数据」从命令行脚本变成页面里可点的真实请求。
 *
 * 这一层只做三件事：查状态、建/删索引、投递 bulk 任务。
 * 具体的索引定义与写入逻辑都在 DatasetService —— 同一份代码，
 * 页面点一次和 sh/seed.sh 跑一次，建出来的索引必须完全一致。
 */
@RestController
public class DataController {

    private final DatasetService dataset;
    private final JobRegistry jobs;
    private final ObjectMapper mapper;
    private final Path root;

    public DataController(DatasetService dataset, JobRegistry jobs, ObjectMapper mapper, ProjectRoot projectRoot) {
        this.dataset = dataset;
        this.jobs = jobs;
        this.mapper = mapper;
        this.root = projectRoot.path();
    }

    @GetMapping("/api/data")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", DatasetService.DEFAULT_TARGET);
        out.put("full", DatasetService.FULL_TARGET);
        out.put("v1", dataset.describe(DatasetService.V1));
        out.put("v2", dataset.describe(DatasetService.V2));
        // 第 8-11 章的实验索引不在 v1/v2 里，前置检查要靠这两块判断
        out.put("indices", dataset.listDetailed());
        out.put("aliases", dataset.listAliases());
        out.put("seedMeta", readJson(root.resolve("var/seed.json")));
        return out;
    }

    /**
     * 字段字典：速查页与控制台的字段提示都读这里。
     *
     * 类型从真实 mapping 读，所以 v1（动态映射）与 v2（显式 Mapping）会各自如实反映结果 ——
     * 页面不再硬编码一份可能与索引对不上的字段表。
     */
    @GetMapping("/api/data/fields")
    public Map<String, Object> fields() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("v1", dataset.fieldDict(DatasetService.V1));
        out.put("v2", dataset.fieldDict(DatasetService.V2));
        return out;
    }

    @PostMapping("/api/data/{version}/create")
    public ResponseEntity<?> create(@PathVariable String version) {
        try {
            if (!dataset.exists(dataset.indexOf(version))) {
                dataset.create(version);
            }
            return ResponseEntity.ok(Map.of("ok", true, "index", dataset.describe(dataset.indexOf(version))));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    @DeleteMapping("/api/data/{version}")
    public ResponseEntity<?> drop(@PathVariable String version) {
        try {
            String index = dataset.indexOf(version);
            dataset.deleteIfExists(index);
            return ResponseEntity.ok(Map.of("ok", true, "merged", index));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    /** 投递 bulk 任务：立即返回 jobId，进度由 /api/jobs/{id} 轮询 */
    @PostMapping("/api/data/{version}/bulk")
    public ResponseEntity<?> bulk(@PathVariable String version, @RequestBody(required = false) Map<String, Object> req) {
        int count = DatasetService.DEFAULT_TARGET;
        if (req != null && req.get("count") instanceof Number n) count = n.intValue();
        if (count <= 0 || count > 500_000) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "count 必须在 1..500000 之间"));
        }
        final int target = count;
        try {
            String index = dataset.indexOf(version);
            if (!dataset.exists(index)) {
                return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "索引 " + index + " 还不存在，先创建"));
            }
            // 已经够了就不重复灌：重复执行必须幂等，否则学员多点两次就变成 4 万条
            long existing = dataset.count(index);
            if (existing < 0) {
                return ResponseEntity.status(500)
                        .body(Map.of("ok", false, "error", "读不到 " + index + " 的文档数，稍后重试"));
            }
            if (existing >= target) {
                return ResponseEntity.ok(Map.of(
                        "ok", true, "skipped", true,
                        "docs", existing,
                        "message", "已有 " + existing + " 条，达到或超过目标 " + target + "，跳过写入"));
            }
            int startFrom = (int) existing;
            int remaining = (int) (target - existing);
            JobRegistry.Job job = jobs.submit("bulk:" + index, remaining, j -> {
                DatasetService.BulkOutcome o = dataset.bulkLoad(index, remaining, startFrom, j::progress);
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("written", o.written());
                r.put("ms", o.ms());
                r.put("rate", o.rate());
                r.put("segments", o.segments());
                r.put("storeBytes", o.storeBytes());
                r.put("docs", o.docs());
                return r;
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("jobId", job.id);
            out.put("total", remaining);
            out.put("startFrom", startFrom);
            return ResponseEntity.accepted().body(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    @GetMapping("/api/jobs/{id}")
    public ResponseEntity<?> job(@PathVariable String id) {
        JobRegistry.Job job = jobs.get(id);
        if (job == null) return ResponseEntity.status(404).body(Map.of("error", "任务不存在或已过期"));
        return ResponseEntity.ok(job.view());
    }

    private JsonNode readJson(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            return mapper.readTree(Files.readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出当前 eslab-* 索引，供路线图展示「你手上有什么」 */
    @GetMapping("/api/data/indices")
    public Map<String, Object> indices() {
        List<String> list = dataset.indices();
        return Map.of("indices", list);
    }
}
