package com.eslab.web;

import com.eslab.lib.ProjectRoot;
import com.eslab.experiments.ExperimentRegistry;
import com.eslab.levels.LevelRegistry;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.EsResponse;
import com.eslab.lib.Probe;
import com.eslab.model.ExperimentModule;
import com.eslab.model.ExperimentResult;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个很薄的 HTTP 层：白名单代理、关卡编排、实验编排、源码回读。
 *
 * 浏览器永远不直连 9200 —— 既避开 CORS，也保证「只能碰 eslab-* 前缀索引」这条约束
 * 在服务端强制执行，而不是靠前端自觉。
 */
@RestController
public class ApiController {

    private static final String PREFIX = "eslab-";

    private final EsClient es;
    private final LevelRegistry levels;
    private final ExperimentRegistry experiments;
    private final ObjectMapper mapper;
    private final Path root;

    public ApiController(EsClient es,
                         LevelRegistry levels,
                         ExperimentRegistry experiments,
                         ObjectMapper mapper,
                         ProjectRoot projectRoot) {
        this.es = es;
        this.levels = levels;
        this.experiments = experiments;
        this.mapper = mapper;
        this.root = projectRoot.path();
    }

    // ── 健康检查 / 环境概览 ─────────────────────────────────
    @GetMapping("/api/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cluster", cluster());
        out.put("indices", indices());
        out.put("seedMeta", readJson(root.resolve("var/seed.json")));
        out.put("esUrl", es.baseUrl());
        out.put("levels", levels.ids());
        out.put("experiments", experiments.brief());
        return out;
    }

    private Map<String, Object> cluster() {
        Map<String, Object> c = new LinkedHashMap<>();
        try {
            JsonNode info = es.get("/");
            JsonNode h = es.get("/_cluster/health");
            c.put("up", true);
            c.put("version", info.path("version").path("number").asText("?"));
            c.put("clusterName", info.path("cluster_name").asText("?"));
            c.put("health", h.path("status").asText("?"));
            c.put("nodes", h.path("number_of_nodes").asInt(0));
        } catch (Exception e) {
            c.put("up", false);
            c.put("error", e.getMessage());
        }
        return c;
    }

    private List<Map<String, Object>> indices() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/indices/" + PREFIX
                    + "*?format=json&h=index,health,pri,rep,docs.count,store.size,segments.count");
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("index", r.path("index").asText());
                    row.put("health", r.path("health").asText());
                    row.put("docs", r.path("docs.count").asLong(0));
                    row.put("storeBytes", parseSize(r.path("store.size").asText("0b")));
                    row.put("segments", r.path("segments.count").asLong(0));
                    rows.add(row);
                }
            }
        } catch (Exception ignored) {
            // ES 未启动时返回空列表，由前端展示「ES 离线」
        }
        return rows;
    }

    private static long parseSize(String s) {
        String v = s.trim().toLowerCase();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^([\\d.]+)(b|kb|mb|gb|tb)$").matcher(v);
        if (!m.matches()) {
            try {
                return (long) Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        double n = Double.parseDouble(m.group(1));
        double mul = switch (m.group(2)) {
            case "kb" -> 1024d;
            case "mb" -> 1024d * 1024;
            case "gb" -> 1024d * 1024 * 1024;
            case "tb" -> 1024d * 1024 * 1024 * 1024;
            default -> 1d;
        };
        return Math.round(n * mul);
    }

    // ── 知识内容（本地 JSON，离线可用）────────────────────────
    @GetMapping("/api/knowledge")
    public ResponseEntity<?> knowledge() {
        JsonNode node = readClasspathJson("knowledge/modules.json");
        if (node == null) {
            return ResponseEntity.status(500).body(Map.of("error", "读取 classpath:knowledge/modules.json 失败"));
        }
        return ResponseEntity.ok(node);
    }

    // ── 关卡 ────────────────────────────────────────────────
    @GetMapping("/api/levels/{id}/source")
    public ResponseEntity<?> source(@PathVariable String id) {
        String rel = levels.sourceFileOf(id);
        if (rel == null) {
            return ResponseEntity.status(404).body(Map.of("error", "未知关卡 " + id));
        }
        try {
            String code = Files.readString(root.resolve(rel));
            String[] lines = code.split("\n", -1);

            // 解析 // @focus-start / @focus-end 标记，前端据此高亮「本关真正要看的那几行」
            List<Map<String, Integer>> focus = new ArrayList<>();
            Integer open = null;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("@focus-start")) {
                    open = i + 2; // 从下一行开始（转 1-based）
                } else if (lines[i].contains("@focus-end") && open != null) {
                    focus.add(Map.of("start", open, "end", i));
                    open = null;
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("file", rel);
            out.put("lines", lines.length);
            out.put("focus", focus);
            out.put("code", code);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "读取源码失败：" + e.getMessage()));
        }
    }

    @PostMapping("/api/levels/{id}/run")
    public ResponseEntity<?> runLevel(@PathVariable String id) {
        LevelModule module = levels.get(id);
        if (module == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "未知关卡 " + id));
        }
        long started = System.currentTimeMillis();
        try {
            LevelResult result = module.run();
            long elapsed = System.currentTimeMillis() - started;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", id);
            out.put("result", result);
            out.put("elapsedMs", elapsed);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - started;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("id", id);
            out.put("error", String.valueOf(e.getMessage()));
            out.put("hint", e instanceof EsException
                    ? "这是 ES 返回的错误，详情见 error 字段；常见原因：索引未导入（先跑 seed）或字段名/类型不匹配。"
                    : "后端执行异常，详情见 error 字段。");
            out.put("elapsedMs", elapsed);
            return ResponseEntity.status(500).body(out);
        }
    }

    // ── 实验 ────────────────────────────────────────────────
    @GetMapping("/api/experiments")
    public Map<String, Object> listExperiments() {
        return Map.of("experiments", experiments.brief());
    }

    @PostMapping("/api/experiments/{id}/run")
    public ResponseEntity<?> runExperiment(@PathVariable String id) {
        ExperimentModule exp = experiments.get(id);
        if (exp == null) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "未知实验 " + id));
        }
        long started = System.currentTimeMillis();
        try {
            ExperimentResult result = exp.run();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", id);
            out.put("result", result);
            out.put("elapsedMs", System.currentTimeMillis() - started);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("id", id);
            out.put("error", String.valueOf(e.getMessage()));
            out.put("elapsedMs", System.currentTimeMillis() - started);
            return ResponseEntity.status(500).body(out);
        }
    }

    // ── 自由控制台（白名单）──────────────────────────────────
    private static final List<String> ALLOWED_PREFIXES = List.of(
            "/_search", "/_count", "/_analyze", "/_field_caps", "/_mapping", "/_settings",
            "/_stats", "/_pit", "/_bulk", "/_cat/", "/_cluster/health", "/_cluster/stats",
            "/_nodes/stats", "/eslab-",
            // 第 8-11 章：别名、重建、任务、模板、摄入管道。
            // 刻意不放行 /_forcemerge 与 /_rollover 的全局路径 —— 索引级
            // /eslab-xxx/_forcemerge 已经被 /eslab- 前缀覆盖，加全局前缀会让控制台能合并所有索引。
            "/_reindex", "/_tasks/", "/_aliases", "/_alias",
            "/_index_template", "/_ingest/pipeline");

    private static String checkAllowed(String method, String path) {
        if (!path.startsWith("/")) return "path 必须以 / 开头";
        if (path.length() > 600) return "path 过长";

        boolean touchesIndex = path.length() > 1 && path.charAt(1) != '_';
        if (touchesIndex && !path.startsWith("/" + PREFIX)) {
            return "只允许操作 " + PREFIX + "* 前缀的索引";
        }
        if ("DELETE".equals(method)) {
            if (path.matches("^/eslab-[a-zA-Z0-9._-]+$")) return null;
            if ("/_pit".equals(path)) return null;
            return "DELETE 仅允许删除单个 eslab-* 索引或关闭 PIT";
        }
        if ("/".equals(path)) return null;
        for (String p : ALLOWED_PREFIXES) {
            if (path.startsWith(p)) return null;
        }
        return "路径不在白名单内：" + path;
    }

    /**
     * 请求体校验：白名单只看 path，但 reindex / aliases / template 都能在 body 里指定索引。
     *
     * 只放行 path 等于给控制台开了「写任意索引」的口子 —— 本机可能还有别的业务索引。
     * 这里把 body 里的索引名一并限制在 eslab- 前缀内，模板还要拒绝 "*" 这种通配。
     */
    private String checkBody(String path, Object body) {
        if (body == null) return null;
        JsonNode b;
        try {
            b = mapper.valueToTree(body);
        } catch (Exception e) {
            return "请求体无法解析";
        }

        if (path.startsWith("/_reindex")) {
            String src = firstIndex(b.path("source").path("index"));
            if (src == null) return "reindex 必须显式指定 source.index";
            if (!src.startsWith(PREFIX)) return "reindex 的 source.index 只允许 " + PREFIX + "*";
            String dest = b.path("dest").path("index").asText("");
            if (!dest.startsWith(PREFIX)) return "reindex 的 dest.index 只允许 " + PREFIX + "*";
        }

        if (path.startsWith("/_alias")) {
            for (JsonNode action : b.path("actions")) {
                for (String op : List.of("add", "remove")) {
                    JsonNode node = action.path(op);
                    if (node.isMissingNode() || node.isNull()) continue;
                    JsonNode idx = node.path("index");
                    if (idx.isTextual() && !idx.asText().startsWith(PREFIX)) {
                        return "别名只能操作 " + PREFIX + "* 索引";
                    }
                    for (JsonNode i : node.path("indices")) {
                        if (!i.asText().startsWith(PREFIX)) return "别名只能操作 " + PREFIX + "* 索引";
                    }
                }
            }
        }

        if (path.startsWith("/_index_template/_simulate_index/")) {
            String target = path.substring("/_index_template/_simulate_index/".length());
            if (!target.startsWith(PREFIX)) return "只能模拟 " + PREFIX + "* 索引";
        }

        if (path.startsWith("/_index_template")) {
            for (JsonNode p : b.path("index_patterns")) {
                String pattern = p.asText("");
                if (!pattern.startsWith(PREFIX) || pattern.length() <= PREFIX.length()) {
                    return "模板 index_patterns 只允许 " + PREFIX + "* 形式";
                }
            }
            for (JsonNode p : b.path("template").path("index_patterns")) {
                String pattern = p.asText("");
                if (!pattern.startsWith(PREFIX) || pattern.length() <= PREFIX.length()) {
                    return "模板 index_patterns 只允许 " + PREFIX + "* 形式";
                }
            }
        }

        return null;
    }

    private static String firstIndex(JsonNode n) {
        if (n.isTextual()) return n.asText();
        if (n.isArray() && !n.isEmpty()) return n.get(0).asText();
        return null;
    }

    @PostMapping("/api/console")
    public ResponseEntity<?> console(@RequestBody(required = false) Map<String, Object> req) {
        if (req == null || !(req.get("path") instanceof String path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 path"));
        }
        String method = String.valueOf(req.getOrDefault("method", "POST")).toUpperCase();
        String denied = checkAllowed(method, path);
        if (denied == null) denied = checkBody(path, req.get("body"));
        if (denied != null) {
            return ResponseEntity.status(403).body(Map.of("error", denied));
        }
        try {
            EsResponse r = es.call(method, path, req.get("body"));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("ms", round1(r.ms()));
            out.put("status", r.status());
            out.put("data", r.body());
            return ResponseEntity.ok(out);
        } catch (EsException e) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("ms", 0);
            out.put("status", e.status());
            out.put("error", e.getMessage());
            out.put("data", e.payload());
            return ResponseEntity.ok(out);
        }
    }

    // ── 变体实验探针（关卡内一键执行，返回摘要指标）──────────
    @PostMapping("/api/probe")
    public ResponseEntity<?> probe(@RequestBody(required = false) Map<String, Object> req) {
        if (req == null || !(req.get("path") instanceof String path)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 path"));
        }
        String method = String.valueOf(req.getOrDefault("method", "POST")).toUpperCase();
        String denied = checkAllowed(method, path);
        if (denied == null) denied = checkBody(path, req.get("body"));
        if (denied != null) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "status", 0, "ms", 0, "error", denied));
        }
        try {
            EsResponse r = es.call(method, path, req.get("body"), null, 60_000);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("status", r.status());
            out.put("ms", round1(r.ms()));
            out.put("summary", Probe.summarize(r.body()));
            out.put("data", r.body());
            return ResponseEntity.ok(out);
        } catch (EsException e) {
            // 4xx/5xx 不当作接口失败：探针经常故意触发报错（例如 text 字段排序），
            // 错误本身就是学习材料，照常返回，由前端展示 status 与原因。
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("status", e.status());
            out.put("ms", 0);
            out.put("error", e.getMessage());
            out.put("summary", Probe.summarize(e.payload()));
            out.put("data", e.payload());
            return ResponseEntity.ok(out);
        }
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    private JsonNode readJson(Path path) {
        try {
            if (!Files.isRegularFile(path)) return null;
            return mapper.readTree(Files.readString(path));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读 classpath 里的 JSON。
     *
     * 教学内容（knowledge/*.json）属于后端资源，而不是项目根的散装文件：
     * 放进 backend/src/main/resources 后，打包成 jar 也自带，不再依赖运行目录。
     */
    private JsonNode readClasspathJson(String location) {
        try {
            ClassPathResource res = new ClassPathResource(location);
            if (!res.exists()) return null;
            try (InputStream in = res.getInputStream()) {
                return mapper.readTree(in);
            }
        } catch (Exception e) {
            return null;
        }
    }
}
