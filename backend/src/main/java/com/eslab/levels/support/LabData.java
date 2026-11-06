package com.eslab.levels.support;

import com.eslab.data.DatasetService;
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

/**
 * 第 8-11 章的实验数据设施：索引定义、幂等建库、reindex、别名切换。
 *
 * 为什么单独一件事，而不是塞进 DatasetService：
 *   DatasetService 的语义是「课程主数据集 v1/v2」——第 0 章的建库、seed 脚本、数据工坊都依赖它。
 *   把这八九个演示索引混进去，describe()/fieldDict() 就会开始回答两个不同的问题。
 *
 * 三条硬约束：
 *   1. 只读写 eslab-goods* 前缀，写方法直接拒绝其他名字（防止误伤 v1/v2 与别人的索引）；
 *   2. 每个索引一把锁，双击「运行本关」不会并发建同一个索引；
 *   3. 一切幂等：已达标就跳过，不达标就补齐 —— 学员重复点不会变成两倍数据。
 */
@Component
public class LabData {

    public static final String PREFIX = "eslab-goods";
    public static final String ALIAS = "eslab-goods";

    public static final String V3 = "eslab-goods-v3";
    public static final String V4 = "eslab-goods-v4";
    public static final String V5 = "eslab-goods-v5";
    public static final String V6 = "eslab-goods-v6";
    public static final String SEARCH = "eslab-goods-search";
    public static final String OPS = "eslab-goods-ops";
    /** M1-M6 核心操作小关卡的独立、可随时复位的数据集。 */
    public static final String CORE = "eslab-goods-core";

    /** 受控分片实验：同一份 mapping 与数据，只差主分片数。 */
    public static final String SHARD1 = "eslab-goods-shard1";
    public static final String SHARD3 = "eslab-goods-shard3";

    public static final String LIVE_ALIAS = "eslab-goods-live";
    public static final String LIVE_1 = "eslab-goods-live-000001";
    public static final String LIVE_2 = "eslab-goods-live-000002";

    /** 派生索引的数据来源：只读 v2，从不写它 */
    public static final String SOURCE = DatasetService.V2;
    public static final int DOCS = 20_000;
    public static final int OPS_DOCS = 2_000;
    public static final int LIVE_DOCS = 5_000;

    private static final Pattern ALLOWED = Pattern.compile("^eslab-goods(-[a-z0-9._-]+)?$");
    private static final int BATCH = 2_000;

    private final EsClient es;
    private final DatasetService dataset;
    private final ObjectMapper mapper;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public LabData(EsClient es, DatasetService dataset, ObjectMapper mapper) {
        this.es = es;
        this.dataset = dataset;
        this.mapper = mapper;
    }

    // ── 索引定义 ─────────────────────────────────────────────

    /** v3：与 v2 完全同构，只是换了个名字 —— 别名要指向它 */
    public Map<String, Object> v3Definition() {
        return copy(dataset.v2Definition());
    }

    /**
     * v4：在 v2 之上做两处「不能原地改」的变更。
     *
     * 加字段只是表象；真正要讲的是 ignore_above 这类已有字段的参数同样改不了 ——
     * 很多人以为「只改一个参数」可以原地 PUT，实际一样要建新索引。
     */
    public Map<String, Object> v4Definition() {
        Map<String, Object> def = copy(dataset.v2Definition());
        Map<String, Object> props = properties(def);
        Map<String, Object> title = obj(props.get("title"));
        obj(title.get("fields")).put("keyword", Json.map("type", "keyword", "ignore_above", 128));
        props.put("selling_point", Json.map(
                "type", "text", "analyzer", "ik_max_word", "search_analyzer", "ik_smart"));
        return def;
    }

    public Map<String, Object> v5Definition() {
        return v4Definition();
    }

    /** v6：查询期同义词。索引期仍用 ik_max_word，只有搜索期换成带 synonym_graph 的分析器 */
    public Map<String, Object> v6Definition() {
        Map<String, Object> def = v4Definition();
        obj(def.get("settings")).put("analysis", Json.map(
                "filter", Json.map(
                        "goods_synonyms", Json.map(
                                "type", "synonym_graph",
                                "synonyms", List.of(
                                        "蓝牙耳机, TWS, 真无线",
                                        "头戴耳机, 头戴式耳机",
                                        "移动电源, 充电宝"))),
                "analyzer", Json.map(
                        "goods_syn_search", Json.map(
                                "type", "custom",
                                "tokenizer", "ik_smart",
                                "filter", List.of("lowercase", "goods_synonyms")))));
        Map<String, Object> title = obj(properties(def).get("title"));
        title.put("search_analyzer", "goods_syn_search");
        return def;
    }

    /** 相关性实验索引：多两个字段，一个给 bool_prefix 补全，一个给 completion 建议 */
    public Map<String, Object> searchDefinition() {
        Map<String, Object> def = v4Definition();
        Map<String, Object> props = properties(def);
        obj(props.get("title")).put("copy_to", List.of("title_sayt"));
        props.put("title_sayt", Json.map("type", "search_as_you_type", "analyzer", "ik_smart"));
        props.put("title_suggest", Json.map("type", "completion", "analyzer", "ik_smart"));
        return def;
    }

    /** 增量写入实验索引：故意开 dynamic，让手写文档能多加一个 version 字段 */
    public Map<String, Object> opsDefinition() {
        Map<String, Object> def = copy(dataset.v2Definition());
        Map<String, Object> mappings = obj(def.get("mappings"));
        Map<String, Object> props = obj(mappings.get("properties"));
        props.put("version", Json.map("type", "integer"));
        mappings.put("dynamic", true);
        obj(def.get("settings")).put("number_of_shards", 1);
        return def;
    }

    /** 小关卡只需少量稳定样例；不修改课程主索引 v1/v2。 */
    public Map<String, Object> coreDefinition() {
        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map("dynamic", "strict", "properties", Json.map(
                        "sku", Json.map("type", "keyword"),
                        "title", Json.map("type", "text", "analyzer", "ik_max_word",
                                "fields", Json.map("keyword", Json.map("type", "keyword"))),
                        "category", Json.map("type", "keyword"),
                        "brand", Json.map("type", "keyword"),
                        "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                        "stock", Json.map("type", "integer"),
                        "created_at", Json.map("type", "date"))));
    }

    /**
     * 30 条确定性样例，6 个品牌 × 4 个类目 × 价格 99 起每档 +10。
     *
     * 几个小关卡对「基数」有硬要求：composite 分页（m5-5）要能翻到第 2、3 页，
     * cardinality（m5-4）要有可区分的去重结果。品牌只有 2 个时这两关都会退化成一页，
     * 所以这里刻意留出 6 个品牌、4 个类目 —— 数据量小，但分布能支撑桶遍历。
     */
    public Ensure resetCore() {
        recreate(CORE, coreDefinition());
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
        bulkWithIds(CORE, ids, docs);
        refresh(CORE);
        return new Ensure(CORE, count(CORE), true, docs.size());
    }

    /**
     * 受控分片实验的索引定义：与 v2 完全同构（同 mapping、同 analyzer、同 doc_values），
     * 只把主分片数换成指定值。m7-1 用它把「分片数」这一个变量单独拎出来。
     */
    public Map<String, Object> shardLabDefinition(int shards) {
        Map<String, Object> def = copy(dataset.v2Definition());
        obj(def.get("settings")).put("number_of_shards", shards);
        obj(def.get("settings")).put("number_of_replicas", 0);
        return def;
    }

    /** 滚动索引的初始索引：单分片，带写别名 */
    public Map<String, Object> liveDefinition() {
        Map<String, Object> def = copy(dataset.v2Definition());
        obj(def.get("settings")).put("number_of_shards", 1);
        return def;
    }

    /** 建索引时就把写别名挂上，rollover 要求别名指向的索引是唯一的写目标 */
    public Map<String, Object> liveDefinitionWithAlias() {
        Map<String, Object> def = liveDefinition();
        def.put("aliases", Json.map(LIVE_ALIAS, Json.map("is_write_index", true)));
        return def;
    }

    // ── 建库与灌数据 ─────────────────────────────────────────

    public record Ensure(String index, long docs, boolean created, int loaded) {
    }

    public record Reindex(long took, long total, long created, long updated, long deleted,
                          long batches, long conflicts, JsonNode raw) {
    }

    public record Sample(long atMs, boolean completed, long created, long batches) {
    }

    public record AsyncTask(String taskId, List<Sample> samples, boolean completed,
                            long created, long total, long batches, long conflicts, long elapsedMs) {
    }

    public record Swap(boolean acknowledged, boolean errors, int actions, JsonNode raw) {
    }

    public Ensure ensure(String index, Map<String, Object> definition, int target) {
        return ensureDocs(index, definition, target, null);
    }

    /**
     * 幂等建库 + 灌数据。
     *
     * 已经存在的索引不重建（重建会把上一关的别名指向搞乱），只把不足的条数补齐。
     */
    public Ensure ensureDocs(String index, Map<String, Object> definition, int target,
                             IntFunction<Map<String, Object>> factory) {
        requireLab(index);
        ReentrantLock lock = lockOf(index);
        lock.lock();
        try {
            boolean created = false;
            long have = 0;
            if (!exists(index)) {
                es.put("/" + index, definition);
                created = true;
            } else {
                have = Math.max(0, count(index));
            }
            int loaded = 0;
            if (have < target) {
                loaded = bulkGenerate(index, (int) have, target - (int) have, factory);
                refresh(index);
            }
            return new Ensure(index, count(index), created, loaded);
        } finally {
            lock.unlock();
        }
    }

    /** 幂等 reindex 建库：目标已达标就跳过 */
    public Ensure ensureByReindex(String index, Map<String, Object> definition,
                                  String source, int target, String scriptSource) {
        requireLab(index);
        ReentrantLock lock = lockOf(index);
        lock.lock();
        try {
            boolean created = false;
            long have = 0;
            if (!exists(index)) {
                es.put("/" + index, definition);
                created = true;
            } else {
                have = Math.max(0, count(index));
            }
            int loaded = 0;
            if (have < target) {
                Reindex r = reindex(source, index, target, scriptSource);
                loaded = (int) r.created();
                refresh(index);
            }
            return new Ensure(index, count(index), created, loaded);
        } finally {
            lock.unlock();
        }
    }

    public void recreate(String index, Map<String, Object> definition) {
        requireLab(index);
        ReentrantLock lock = lockOf(index);
        lock.lock();
        try {
            delete(index);
            es.put("/" + index, definition);
        } finally {
            lock.unlock();
        }
    }

    public void delete(String index) {
        requireLab(index);
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

    public long count(String index) {
        try {
            return es.get("/" + index + "/_count").path("count").asLong(0);
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

    /**
     * 相关性实验索引：v4 的全部字段 + search_as_you_type + completion。
     *
     * completion 的值没法用 copy_to 从 text 字段自动得到（类型不同），
     * 所以在 reindex 的 script 里补一份。
     */
    public Ensure ensureSearch() {
        return ensureByReindex(SEARCH, searchDefinition(), SOURCE, DOCS,
                "ctx._source.title_suggest = ctx._source.title");
    }

    public void refresh(String index) {
        try {
            es.post("/" + index + "/_refresh", null);
        } catch (Exception ignored) {
            // 索引刚删掉时 refresh 会 404，不影响结论
        }
    }

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
            if (res.path("errors").asBoolean(false)) {
                throw new IllegalStateException("批量写入失败：" + res.path("items").path(0));
            }
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
        if (res.path("errors").asBoolean(false)) {
            throw new IllegalStateException("批量写入失败：" + res.path("items").path(0));
        }
        return docs.size();
    }

    /**
     * 复位增量写入实验用的 ops 索引：2000 条标准商品 + 10 条固定 id 的运营文档。
     *
     * 每关都从头开始，`_version`、`_seq_no`、库存基数才是确定的 ——
     * 否则第二次点「运行本关」会看到上次留下的版本号。
     */
    public Ensure resetOps() {
        recreate(OPS, opsDefinition());
        bulkGenerate(OPS, 0, OPS_DOCS, null);
        List<String> ids = new ArrayList<>();
        List<Map<String, Object>> docs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ids.add("ops-" + i);
            docs.add(opsDoc(i));
        }
        bulkWithIds(OPS, ids, docs);
        refresh(OPS);
        return new Ensure(OPS, count(OPS), true, OPS_DOCS + 10);
    }

    /** 直接发一段 NDJSON（混合动作演示用）：刻意制造的冲突不能当异常抛出去 */
    public JsonNode bulkRaw(String ndjson) {
        return es.bulk(ndjson, 120_000);
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

    // ── reindex / 别名 ───────────────────────────────────────

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
        requireLab(dest);
        Map<String, Object> body = Json.map(
                "source", Json.map("index", source),
                "dest", Json.map("index", dest),
                "max_docs", maxDocs);
        JsonNode start = es.post("/_reindex?wait_for_completion=false&requests_per_second="
                + docsPerSecond, body, 60_000);
        String taskId = start.path("task").asText("");
        if (taskId.isEmpty()) {
            throw new IllegalStateException("_reindex 没有返回 task id：" + start);
        }

        List<Sample> samples = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        boolean completed = false;
        long created = 0, total = 0, batches = 0, conflicts = 0;
        for (int i = 0; i < 120; i++) {
            JsonNode d = es.get("/_tasks/" + taskId);
            JsonNode st = d.path("task").path("status");
            completed = d.path("completed").asBoolean(false);
            created = st.path("created").asLong();
            total = st.path("total").asLong();
            batches = st.path("batches").asLong();
            conflicts = st.path("version_conflicts").asLong();
            samples.add(new Sample(System.currentTimeMillis() - t0, completed, created, batches));
            if (completed) break;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        refresh(dest);
        return new AsyncTask(taskId, samples, completed, created, total, batches, conflicts,
                System.currentTimeMillis() - t0);
    }

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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> copy(Map<String, Object> src) {
        return mapper.convertValue(src, Map.class);
    }

    private static Map<String, Object> properties(Map<String, Object> definition) {
        return obj(obj(definition.get("mappings")).get("properties"));
    }
}
