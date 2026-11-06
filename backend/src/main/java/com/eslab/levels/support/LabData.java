package com.eslab.levels.support;

import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.seed.Generator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static com.eslab.levels.support.LabIndexes.ALIAS;
import static com.eslab.levels.support.LabIndexes.CORE;
import static com.eslab.levels.support.LabIndexes.OPS;
import static com.eslab.levels.support.LabIndexes.OPS_DOCS;
import static com.eslab.levels.support.LabIndexes.PREFIX;
import static com.eslab.levels.support.LabIndexes.SOURCE;

/**
 * 实验索引的执行器：建库、灌数据、reindex、别名切换。
 *
 * 索引定义与生命周期不在这里 —— 全部在 {@link LabIndexes}。这个类只做三件事：
 * 拿锁、按登记处的定义执行、把结果包装成可核验的对象。
 *
 * 语义上只有两种「保证达标」的写法：
 *   · ensure*  —— 已达标就跳过，条数不符（多或少）就整条重建；
 *   · rebuild* —— 无条件清空重来。
 * 刻意不提供「补差额」：目标里那几千条可能是上次中断留下的，无法证明它们对应源的前几千条，
 * 补出来的数字既不可解释也不可复现。2 万条本机重搬 1-3 秒，比数字对不上便宜得多。
 */
@Component
public class LabData {

    private static final Pattern ALLOWED = Pattern.compile("^eslab-goods(-[a-z0-9._-]+)?$");
    private static final int BATCH = 2_000;

    private final EsClient es;
    private final LabIndexes indexes;
    private final ObjectMapper mapper;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LabData(EsClient es, LabIndexes indexes, ObjectMapper mapper) {
        this.es = es;
        this.indexes = indexes;
        this.mapper = mapper;
    }

    // ── 记录 ────────────────────────────────────────────────

    /** shortfall=true 表示源索引不足目标条数，本次只能建到源的实际条数 */
    public record Ensure(String index, long docs, boolean created, int loaded, boolean shortfall) {
        public Ensure(String index, long docs, boolean created, int loaded) {
            this(index, docs, created, loaded, false);
        }
    }

    public record Reindex(long took, long total, long created, long updated, long deleted,
                          long batches, long conflicts, JsonNode raw) {
    }

    public record Sample(long atMs, boolean completed, long created, long batches) {
    }

    /**
     * 异步任务的终态。三个「不正常」的结束方式各有独立字段：
     * error（任务本身报错）、failures（部分文档失败）、timedOut/canceled（轮询耗尽后主动取消）。
     * 只读 completed 会把这三种情况都当成成功 —— 这正是 m8-3 之前最大的问题。
     */
    public record AsyncTask(String taskId, List<Sample> samples, boolean completed, boolean timedOut,
                            boolean canceled, JsonNode error, List<JsonNode> failures,
                            long created, long updated, long total, long batches, long conflicts,
                            int slices, long elapsedMs) {

        public boolean errored() {
            return error != null && !error.isMissingNode() && !error.isNull();
        }

        public boolean anyFailure() {
            return !failures.isEmpty();
        }
    }

    public record Swap(boolean acknowledged, boolean errors, int actions, JsonNode raw) {
    }

    /** 重建时要灌的数据由调用方提供：返回实际写入条数 */
    @FunctionalInterface
    public interface Filler {
        int fill(String index);
    }

    // ── 建库：两种保证 ───────────────────────────────────────

    /** 达标就跳过，条数不符就整条重建（用 Generator 生成标准商品文档） */
    public Ensure ensure(String name) {
        LabIndexes.IndexSpec spec = indexes.require(name);
        return fillTo(spec, false, idx -> bulkGenerate(idx, 0, spec.targetDocs(), null));
    }

    /** 无条件清空重来，灌什么由调用方决定 */
    public Ensure rebuild(String name, Filler filler) {
        LabIndexes.IndexSpec spec = indexes.require(name);
        return fillTo(spec, true, filler);
    }

    /** 从登记处声明的源 reindex：V2 或别名当前指向 */
    public Ensure ensureByReindex(String name) {
        return ensureByReindex(name, null);
    }

    public Ensure ensureByReindex(String name, String scriptSource) {
        return reindexTo(indexes.require(name), false, scriptSource);
    }

    /** 无条件从源重搬（v5 这类每次都要重来的目标） */
    public Ensure rebuildByReindex(String name, String scriptSource) {
        return reindexTo(indexes.require(name), true, scriptSource);
    }

    private Ensure fillTo(LabIndexes.IndexSpec spec, boolean force, Filler filler) {
        String name = spec.name();
        requireLab(name);
        ReentrantLock lock = lockOf(name);
        lock.lock();
        try {
            Map<String, Boolean> aliases = aliasesOfIndex(name);
            boolean exists = exists(name);
            if (exists && !force) {
                long have = count(name);
                if (have == spec.targetDocs()) {
                    return new Ensure(name, have, false, 0, false);
                }
            }
            if (exists) deleteLocked(name);
            es.put("/" + name, spec.newDefinition());
            restoreAliases(name, aliases);
            int loaded = filler.fill(name);
            refresh(name);
            long docs = count(name);
            return new Ensure(name, docs, true, loaded, docs != spec.targetDocs());
        } finally {
            lock.unlock();
        }
    }

    private Ensure reindexTo(LabIndexes.IndexSpec spec, boolean force, String scriptSource) {
        String name = spec.name();
        requireLab(name);
        String source = spec.source() == LabIndexes.Source.REINDEX_ALIAS ? ALIAS : SOURCE;

        ReentrantLock lock = lockOf(name);
        lock.lock();
        try {
            long sourceCount = countQuietly(source);
            if (sourceCount <= 0) {
                throw new IllegalStateException("源索引 " + source
                        + " 不存在或没有数据 —— 先完成第 0/1 章把 v2 建起来");
            }
            int want = (int) Math.min(spec.targetDocs(), sourceCount);
            boolean shortfall = sourceCount < spec.targetDocs();

            Map<String, Boolean> aliases = aliasesOfIndex(name);
            boolean exists = exists(name);
            if (exists && !force) {
                long have = count(name);
                if (have == want) {
                    return new Ensure(name, have, false, 0, shortfall);
                }
            }
            if (exists) deleteLocked(name);
            es.put("/" + name, spec.newDefinition());
            restoreAliases(name, aliases);

            Reindex r = reindex(source, name, want, scriptSource);
            refresh(name);
            long docs = count(name);
            if (docs != want) {
                throw new IllegalStateException("reindex 后条数不符：" + name
                        + " 期望 " + want + "，实际 " + docs
                        + "（created=" + r.created() + " failures 见原始响应）");
            }
            return new Ensure(name, docs, true, (int) r.created(), shortfall);
        } finally {
            lock.unlock();
        }
    }

    public void delete(String index) {
        requireLab(index);
        ReentrantLock lock = lockOf(index);
        lock.lock();
        try {
            deleteLocked(index);
        } finally {
            lock.unlock();
        }
    }

    private void deleteLocked(String index) {
        if (exists(index)) es.delete("/" + index);
    }

    public boolean exists(String index) {
        try {
            es.call("HEAD", "/" + index, null, null, 5_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 写路径专用：索引不存在或 ES 报错就抛出去，绝不返回负数让调用方当 0 处理 */
    public long count(String index) {
        return es.get("/" + index + "/_count").path("count").asLong(0);
    }

    /** 展示路径专用：索引不存在时返回 -1，页面自行显示「—」 */
    public long countQuietly(String index) {
        try {
            return count(index);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 带条件的计数：相关性关卡大量用到「同一 query 在不同索引/分析器下命中多少」 */
    public long countWithQuery(String index, Object query) {
        try {
            return es.post("/" + index + "/_count", Json.map("query", query))
                    .path("count").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 相关性实验索引：v4 的全部字段 + search_as_you_type + completion */
    public Ensure ensureSearch() {
        return ensureByReindex(LabIndexes.SEARCH,
                "ctx._source.title_suggest = ctx._source.title");
    }

    public void refresh(String index) {
        try {
            es.post("/" + index + "/_refresh", null);
        } catch (Exception ignored) {
            // 索引刚删掉时 refresh 会 404，不影响结论
        }
    }

    // ── 灌数据 ──────────────────────────────────────────────

    /** 灌 Generator 生成的标准商品文档；factory 为 null 时用默认生成器 */
    public int bulkGenerate(String index, int startFrom, int count, IntFunction<Map<String, Object>> factory) {
        requireLab(index);
        int done = 0;
        while (done < count) {
            int end = Math.min(done + BATCH, count);
            StringBuilder sb = new StringBuilder((end - done) * 700);
            for (int i = startFrom + done; i < startFrom + end; i++) {
                Map<String, Object> doc = factory == null ? productDoc(i) : factory.apply(i);
                sb.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
                try {
                    sb.append(mapper.writeValueAsString(doc)).append('\n');
                } catch (Exception e) {
                    throw new IllegalStateException("序列化文档失败：" + e.getMessage());
                }
            }
            JsonNode res = es.bulk(sb.toString(), 180_000);
            requireBulkOk(index, res, end - done);
            done = end;
        }
        return done;
    }

    /** 带显式 _id 的写入，给 ops-1..ops-10 这类需要稳定 id 的演示用 */
    public int bulkWithIds(String index, List<String> ids, List<Map<String, Object>> docs) {
        requireLab(index);
        StringBuilder sb = new StringBuilder(docs.size() * 700);
        for (int i = 0; i < docs.size(); i++) {
            sb.append("{\"index\":{\"_index\":\"").append(index)
                    .append("\",\"_id\":\"").append(ids.get(i)).append("\"}}\n");
            try {
                sb.append(mapper.writeValueAsString(docs.get(i))).append('\n');
            } catch (Exception e) {
                throw new IllegalStateException("序列化文档失败：" + e.getMessage());
            }
        }
        JsonNode res = es.bulk(sb.toString(), 120_000);
        requireBulkOk(index, res, docs.size());
        return docs.size();
    }

    /** 直接发一段 NDJSON（混合动作演示用）：刻意制造的冲突不能当异常抛出去 */
    public JsonNode bulkRaw(String ndjson) {
        return es.bulk(ndjson, 120_000);
    }

    /**
     * bulk 的部分失败不能当整体成功，也不能只抛第一条了事。
     * 这里统计 2xx 条数，失败时把前三条的 status / error.type / _id 一起带出来。
     */
    private static void requireBulkOk(String index, JsonNode res, int expected) {
        if (!res.path("errors").asBoolean(false)) return;
        List<String> bad = new ArrayList<>();
        for (JsonNode item : res.path("items")) {
            JsonNode node = item.elements().hasNext() ? item.elements().next() : item;
            int status = node.path("status").asInt(0);
            if (status >= 200 && status < 300) continue;
            bad.add(node.path("_id").asText("?") + " → " + status + " "
                    + node.path("error").path("type").asText(""));
            if (bad.size() >= 3) break;
        }
        throw new IllegalStateException("批量写入 " + index + " 有失败项（共 " + expected
                + " 条）：" + String.join("; ", bad));
    }

    /** 一条标准商品文档（与 v2 数据同构）；不要在这里加字段 —— v3/v4/v5 用的是 dynamic: strict */
    public Map<String, Object> productDoc(int i) {
        return mapper.convertValue(Generator.makeDoc(i, 42), Map.class);
    }

    /** ops 索引的前 10 条：库存与版本固定，方便对比「改了几次」 */
    public Map<String, Object> opsDoc(int i) {
        Map<String, Object> doc = new LinkedHashMap<>(productDoc(i));
        doc.put("sku", "OPS-" + i);
        doc.put("title", "运营实验商品 " + i + " 蓝牙耳机");
        doc.put("stock", 100);
        doc.put("version", 1);
        doc.put("status", 1);
        doc.put("price", 199.0 + i);
        return doc;
    }

    // ── 复位：小关卡共用数据集 ───────────────────────────────

    /**
     * 30 条确定性样例，6 个品牌 × 4 个类目 × 价格 99 起每档 +10。
     *
     * 几个小关卡对「基数」有硬要求：composite 分页（m5-5）要能翻到第 2、3 页，
     * cardinality（m5-4）要有可区分的去重结果。品牌只有 2 个时这两关都会退化成一页，
     * 所以这里刻意留出 6 个品牌、4 个类目 —— 数据量小，但分布能支撑桶遍历。
     */
    public Ensure resetCore() {
        return rebuild(CORE, idx -> {
            String[] brands = {"拾光", "云声", "北极星", "木言", "星河", "松果"};
            String[] categories = {"数码影音", "电脑办公", "家居", "运动"};
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                ids.add("core-" + i);
                docs.add(Json.map("sku", "CORE-" + i,
                        "title", i % 2 == 0 ? "蓝牙耳机 核心样例 " + i : "机械键盘 核心样例 " + i,
                        "category", categories[i % categories.length],
                        "brand", brands[i % brands.length],
                        "price", 99.0 + i * 10,
                        "stock", 10 + i,
                        "created_at", "2026-01-" + String.format("%02d", (i % 28) + 1)));
            }
            bulkWithIds(idx, ids, docs);
            return docs.size();
        });
    }

    /**
     * 复位增量写入实验用的 ops 索引：2000 条标准商品 + 10 条固定 id 的运营文档。
     *
     * 每关都从头开始，`_version`、`_seq_no`、库存基数才是确定的 ——
     * 否则第二次点「运行本关」会看到上次留下的版本号。
     */
    public Ensure resetOps() {
        return rebuild(OPS, idx -> {
            int n = bulkGenerate(idx, 0, OPS_DOCS, null);
            List<String> ids = new ArrayList<>();
            List<Map<String, Object>> docs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                ids.add("ops-" + i);
                docs.add(opsDoc(i));
            }
            bulkWithIds(idx, ids, docs);
            return n + 10;
        });
    }

    // ── reindex ────────────────────────────────────────────

    public Reindex reindex(String source, String dest, int maxDocs, String scriptSource) {
        requireLab(dest);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source", Json.map("index", source));
        body.put("dest", Json.map("index", dest));
        body.put("max_docs", maxDocs);
        if (scriptSource != null) {
            body.put("script", Json.map("source", scriptSource));
        }
        JsonNode r = es.post("/_reindex?refresh=true", body, 180_000);
        return new Reindex(
                r.path("took").asLong(), r.path("total").asLong(), r.path("created").asLong(),
                r.path("updated").asLong(), r.path("deleted").asLong(), r.path("batches").asLong(),
                r.path("version_conflicts").asLong(), r);
    }

    /**
     * 异步 reindex + 轮询 _tasks。
     *
     * 20k 在本机本来就 1-2 秒，直接同步跑看不到「进行中」。
     * 用 requests_per_second 限速把总时长拉到 4-6 秒，轮询才真的能观察到中间态。
     */
    public AsyncTask reindexAsync(String source, String dest, int maxDocs, double docsPerSecond) {
        return reindexAsync(source, dest, maxDocs, docsPerSecond, 30_000, 1);
    }

    /**
     * 异步 reindex + 轮询 _tasks，并把三种「不成功」分开返回。
     *
     * @param timeoutMs 轮询上限；到点还没完成就调用 _cancel，而不是继续等到天荒地老
     * @param slices    并行切片数（1 = 不分片）
     */
    public AsyncTask reindexAsync(String source, String dest, int maxDocs, double docsPerSecond,
                                  int timeoutMs, int slices) {
        requireLab(dest);
        Map<String, Object> body = Json.map(
                "source", Json.map("index", source),
                "dest", Json.map("index", dest));
        // 实测（ES 8.18.5）：max_docs 与 slices>1 一起用时搬不满 ——
        // 20000 条 slices=2 只搬 16667，slices=4 也 16667，slices=3 是 19998。
        // 想要精确条数就别传 max_docs（源有多少搬多少），或者把 slices 压到 1。
        if (maxDocs > 0) body.put("max_docs", maxDocs);
        String query = "/_reindex?wait_for_completion=false&requests_per_second=" + docsPerSecond;
        if (slices > 1) query += "&slices=" + slices;
        JsonNode start = es.post(query, body, 60_000);
        String taskId = start.path("task").asText("");
        if (taskId.isEmpty()) {
            throw new IllegalStateException("_reindex 没有返回 task id：" + start);
        }

        List<Sample> samples = new ArrayList<>();
        List<JsonNode> failures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        boolean completed = false, timedOut = false, canceled = false;
        JsonNode error = null;
        long created = 0, updated = 0, total = 0, batches = 0, conflicts = 0;

        while (true) {
            JsonNode d = es.get("/_tasks/" + taskId);
            JsonNode st = d.path("task").path("status");
            JsonNode resp = d.path("response");

            completed = d.path("completed").asBoolean(false);
            if (d.has("error") && !d.path("error").isNull()) {
                error = d.path("error");
            }
            created = st.path("created").asLong();
            updated = resp.path("updated").asLong(st.path("updated").asLong());
            total = st.path("total").asLong();
            batches = st.path("batches").asLong();
            conflicts = st.path("version_conflicts").asLong();

            samples.add(new Sample(System.currentTimeMillis() - t0, completed, created, batches));

            // 只在完成时才有 response.failures；进行中读到的永远是空
            if (completed) {
                for (JsonNode f : resp.path("failures")) failures.add(f);
                break;
            }
            if (error != null) break;

            if (System.currentTimeMillis() - t0 >= timeoutMs) {
                timedOut = true;
                try {
                    es.post("/_tasks/" + taskId + "/_cancel?wait_for_completion=false", null);
                    canceled = true;
                } catch (Exception ignored) {
                    // 任务刚好在取消前完成时，_cancel 可能返回 404；不影响结论
                }
                break;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        refresh(dest);
        return new AsyncTask(taskId, samples, completed, timedOut, canceled, error, failures,
                created, updated, total, batches, conflicts, slices,
                System.currentTimeMillis() - t0);
    }

    // ── 别名 ────────────────────────────────────────────────

    /**
     * 把别名切到 add 指向的索引：当前指向里不是它的，全部 remove，再 add。
     *
     * 两个实测出来的边界：
     *   · remove 一个本来就没挂别名的索引会报 aliases_not_found_exception，所以必须先算 diff；
     *   · add 一个已经指向同一索引的别名是幂等 no-op，不会报错。
     */
    public Swap swapAlias(String alias, String add) {
        List<String> current = new ArrayList<>();
        for (Map<String, Object> row : aliasRows(alias)) {
            current.add(String.valueOf(row.get("index")));
        }
        List<Object> actions = new ArrayList<>();
        for (String target : current) {
            if (!target.equals(add)) {
                actions.add(Json.map("remove", Json.map("index", target, "alias", alias)));
            }
        }
        actions.add(Json.map("add", Json.map("index", add, "alias", alias, "is_write_index", true)));
        JsonNode res = es.post("/_aliases", Json.map("actions", actions));
        // ES 的 _aliases 只回 {acknowledged, errors}，没有 action_results —— 动作数由我们记
        return new Swap(res.path("acknowledged").asBoolean(false),
                res.path("errors").asBoolean(false), actions.size(), res);
    }

    public List<Map<String, Object>> aliasRows(String alias) {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/aliases/" + alias
                    + "?format=json&h=alias,index,is_write_index");
            for (JsonNode r : arr) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("alias", r.path("alias").asText());
                row.put("index", r.path("index").asText());
                row.put("writeIndex", "true".equals(r.path("is_write_index").asText()));
                rows.add(row);
            }
        } catch (Exception ignored) {
            // 别名不存在时返回空列表
        }
        return rows;
    }

    public String writeTargetOf(String alias) {
        for (Map<String, Object> row : aliasRows(alias)) {
            if (Boolean.TRUE.equals(row.get("writeIndex"))) return String.valueOf(row.get("index"));
        }
        return null;
    }

    /** 一次归位的结果：从哪儿来、到哪儿去、有没有真的动过 */
    public record PinResult(String alias, String from, String to, boolean changed) {
    }

    /**
     * 把关卡声明的基线别名幂等归位。
     *
     * 先确保目标索引达标再切 —— 否则第一次运行时目标还不存在，切过去等于把别名挂到空气上。
     */
    public List<PinResult> pinAliases(List<com.eslab.model.AliasBaseline> baselines) {
        List<PinResult> out = new ArrayList<>();
        for (com.eslab.model.AliasBaseline b : baselines) {
            LabIndexes.IndexSpec spec = indexes.require(b.target());
            if (spec.source() == LabIndexes.Source.REINDEX_V2) {
                ensureByReindex(b.target());
            }
            String from = writeTargetOf(b.alias());
            if (b.target().equals(from)) {
                out.add(new PinResult(b.alias(), from, b.target(), false));
                continue;
            }
            Swap swap = swapAlias(b.alias(), b.target());
            out.add(new PinResult(b.alias(), from, b.target(), swap.acknowledged()));
        }
        return out;
    }

    /** 重建索引前先把挂在它身上的别名记下来，重建后按原来的写标记复原 */
    private Map<String, Boolean> aliasesOfIndex(String index) {
        Map<String, Boolean> out = new LinkedHashMap<>();
        try {
            JsonNode arr = es.get("/_cat/aliases?format=json&h=alias,index,is_write_index");
            for (JsonNode r : arr) {
                if (index.equals(r.path("index").asText())) {
                    out.put(r.path("alias").asText(),
                            "true".equals(r.path("is_write_index").asText()));
                }
            }
        } catch (Exception ignored) {
            // 没有别名时返回空
        }
        return out;
    }

    private void restoreAliases(String index, Map<String, Boolean> saved) {
        for (Map.Entry<String, Boolean> e : saved.entrySet()) {
            Map<String, Object> add = Json.map("index", index, "alias", e.getKey());
            if (Boolean.TRUE.equals(e.getValue())) add.put("is_write_index", true);
            try {
                es.post("/_aliases", Json.map("actions", List.of(Json.map("add", add))));
            } catch (Exception ignored) {
                // 索引刚建好就挂别名，失败会让后续 count(alias) 变成 -1，由调用方发现
            }
        }
    }

    // ── 观测 ────────────────────────────────────────────────

    public long indexCount(String index) {
        try {
            JsonNode row = es.get("/_cat/indices/" + index
                    + "?format=json&h=index,docs.count,segments.count").path(0);
            return row.path("docs.count").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    public long segmentCount(String index) {
        try {
            JsonNode row = es.get("/_cat/indices/" + index
                    + "?format=json&h=index,docs.count,segments.count").path(0);
            return row.path("segments.count").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 按通配取索引名，滚动索引关要清掉 -000002 之后的残留 */
    public List<String> indicesOf(String pattern) {
        List<String> out = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/indices/" + pattern + "?format=json&h=index");
            for (JsonNode r : arr) out.add(r.path("index").asText());
        } catch (Exception ignored) {
            // 没有匹配时返回空列表
        }
        return out;
    }

    // ── 内部 ────────────────────────────────────────────────

    private ReentrantLock lockOf(String index) {
        return locks.computeIfAbsent(index, k -> new ReentrantLock());
    }

    private static void requireLab(String index) {
        if (index == null || !ALLOWED.matcher(index).matches()) {
            throw new IllegalArgumentException("只允许操作 " + PREFIX + "* 索引，拒绝：" + index);
        }
    }
}
