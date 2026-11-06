package com.eslab.levels.support;

import com.eslab.data.DatasetService;
import com.eslab.lib.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 全部实验索引的唯一登记处。
 *
 * 为什么要有这个类：索引的四个事实原本散在四处 —— 名字在 LabData 常量里、定义在
 * LabData / DatasetService / 各关卡类里、生命周期藏在各关 run() 的 recreate 调用里、
 * 归属在 modules.json 的 prereq 里。四层各自演进，于是出现了「reindex 不补差额」、
 * 「count 失败被当 0」、「别名指向取决于跑了哪些关」这些问题。
 *
 * 这里只做登记，不做任何 ES 操作 —— 执行仍然归 LabData。
 *
 * 三条约束：
 *   1. 定义只在这里派生，L1/L2 一律 copy(DatasetService.v2Definition())，不复制字段表；
 *   2. 每个索引必须能回答「谁建的、什么生命周期、多少条算达标、属哪一章」；
 *   3. 启动即校验，登记不全直接让 Spring 起不来，不要等到学员点到那一关才 404。
 */
@Component
public class LabIndexes {

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

    /** m11-2：dynamic_templates 的对照与实验组 */
    public static final String DYN_CTRL = "eslab-goods-dyn-ctrl";
    public static final String DYN = "eslab-goods-dyn";
    /** m11-1：模板产物；-2027 只做 _simulate_index，永不创建 */
    public static final String TPL = "eslab-goods-tpl-2026";
    public static final String TPL_PLANNED = "eslab-goods-tpl-2027";
    /** m11-3：pipeline 落库目标 */
    public static final String INGEST = "eslab-goods-ingest";
    public static final int DYN_DOCS = 50;

    /** m8-3：失败与取消两条分支的一次性索引，跑完即删 */
    public static final String REINDEX_FAIL = "eslab-goods-reindex-fail";
    public static final String V5_CANCEL = "eslab-goods-v5-cancel";

    // ── M1/M3 的一次性演示索引 ────────────────────────────────
    // 这些索引只在自家关卡里有意义，但定义必须和别处一样只有一份：
    // 之前它们散落在 5 个关卡类里，同一个「sku/title/price」字段表被抄了 3 遍。
    /** m1-1：只给分片数、不给 mappings，字段类型全交给 ES 猜 */
    public static final String DEMO_AUTO = "eslab-demo-auto";
    /** m1-4：反面教材，属性平铺成字段直到撞上限 */
    public static final String DEMO_EXPLODE = "eslab-demo-explode";
    /** m3-1：单条写 vs bulk 写的对照组 */
    public static final String DEMO_WRITE = "eslab-demo-write";
    /** m3-2：自动 _id 与业务 _id 的写入耗时对照 */
    public static final String DEMO_ID_AUTO = "eslab-demo-id-auto";
    public static final String DEMO_ID_BIZ = "eslab-demo-id-biz";
    /** m3-3：近实时可见性 */
    public static final String DEMO_REFRESH = "eslab-demo-refresh";
    /** 实验 D：refresh_interval 的两个对照索引 */
    public static final String DEMO_REFRESH_ON = "eslab-demo-refresh-on";
    public static final String DEMO_REFRESH_OFF = "eslab-demo-refresh-off";

    /** 派生索引的数据来源：只读 v2，从不写它 */
    public static final String SOURCE = DatasetService.V2;
    public static final int DOCS = 20_000;
    public static final int OPS_DOCS = 2_000;
    public static final int LIVE_DOCS = 5_000;

    /** 生命周期：决定「谁在什么时候该删」。 */
    public enum Lifecycle {
        /** 建一次，后续复用；条数不符才重建 */
        PERSISTENT,
        /** 每次运行先清空再灌 */
        RESET_ON_RUN,
        /** 每次运行先清空，由关卡自己灌 */
        RECREATE_ON_RUN,
        /** 关卡结束删除，异常路径也要删 */
        DROP_AFTER_RUN,
        /** 滚动索引族，写别名指向其中一个 */
        ROLLOVER,
        /** 只 _simulate，永不创建 */
        PLANNED
    }

    /** 重建代价：用于前端提示与「要不要现在重建」的判断。 */
    public enum ResetCost {
        FAST, MEDIUM, SLOW
    }

    /** 数据从哪来。 */
    public enum Source {
        /** 自己生成 */
        SELF,
        /** 从 v2 reindex */
        REINDEX_V2,
        /** 从别名当前指向复制（m9-6） */
        REINDEX_ALIAS
    }

    public record IndexSpec(
            String name,
            String chapter,
            String ownerLevel,
            Lifecycle lifecycle,
            ResetCost resetCost,
            Source source,
            int targetDocs,
            Supplier<Map<String, Object>> definition,
            List<String> aliases,
            boolean labWritable,
            String note) {

        /** 每次返回新的 Map，调用方可以安全修改 */
        public Map<String, Object> newDefinition() {
            return definition.get();
        }
    }

    public record AliasSpec(String name, String defaultTarget, List<String> candidates) {
    }

    /** 模板、ingest pipeline 这类「没有索引实体但有生命周期」的对象 */
    public record AssetSpec(String type, String name, String pattern,
                            String chapter, String ownerLevel) {
    }

    private final DatasetService ds;
    private final ObjectMapper mapper;
    private final Map<String, IndexSpec> indices = new LinkedHashMap<>();
    private final Map<String, AliasSpec> aliases = new LinkedHashMap<>();
    private final Map<String, AssetSpec> assets = new LinkedHashMap<>();

    public LabIndexes(DatasetService ds, ObjectMapper mapper) {
        this.ds = ds;
        this.mapper = mapper;
        register();
        validate();
    }

    // ── 登记 ────────────────────────────────────────────────

    private void register() {
        // L0 主数据：定义仍由 DatasetService 独占，这里只引用
        index(DatasetService.V1, "m0", "m0-2", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.SELF, DatasetService.DEFAULT_TARGET, ds::v1Definition, List.of(), false,
                "主数据集：全动态映射；只有 DatasetService 能写");
        index(DatasetService.V2, "m0", "m1-2", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.SELF, DatasetService.DEFAULT_TARGET, ds::v2Definition, List.of(), false,
                "主数据集：显式 Mapping + ik；全部派生索引的源（默认 2 万，可 seed 到 20 万）");

        // L1 派生基线：版本演进链
        index(V3, "m8", "m8-1", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, this::v3Definition, List.of(ALIAS), true,
                "与 v2 同构，别名首个指向目标");
        index(V4, "m8", "m8-2", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, this::v4Definition, List.of(ALIAS), true,
                "新增 selling_point + title.keyword.ignore_above 128");
        index(V5, "m8", "m8-3", Lifecycle.RECREATE_ON_RUN, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, this::v5Definition, List.of(), true,
                "异步 reindex 的目标，每次重建");
        index(V6, "m9", "m9-6", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_ALIAS, DOCS, this::v6Definition, List.of(ALIAS), true,
                "查询期同义词；源是别名当前指向");

        // L2 专题数据
        index(SEARCH, "m9", "m9-1", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, this::searchDefinition, List.of(), true,
                "search_as_you_type + completion，m9-1..m9-5 共用");
        index(OPS, "m10", "m10-1", Lifecycle.RESET_ON_RUN, ResetCost.MEDIUM,
                Source.SELF, OPS_DOCS + 10, this::opsDefinition, List.of(), true,
                "增量写入实验：2000 条商品 + 10 条固定 id 运营文档");
        index(CORE, "m1", "core", Lifecycle.RESET_ON_RUN, ResetCost.FAST,
                Source.SELF, 30, this::coreDefinition, List.of(), true,
                "M1-M6 十个操作型小关卡共用，30 条确定性样例");
        index(SHARD1, "m7", "m7-1", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, () -> shardLabDefinition(1), List.of(), true,
                "受控分片对照：1 主分片");
        index(SHARD3, "m7", "m7-1", Lifecycle.PERSISTENT, ResetCost.SLOW,
                Source.REINDEX_V2, DOCS, () -> shardLabDefinition(3), List.of(), true,
                "受控分片对照：3 主分片");

        // L3 滚动族
        index(LIVE_1, "m8", "m8-4", Lifecycle.ROLLOVER, ResetCost.MEDIUM,
                Source.SELF, LIVE_DOCS, this::liveDefinitionWithAlias, List.of(LIVE_ALIAS), true,
                "滚动写索引；建时就挂写别名");
        index(LIVE_2, "m8", "m8-4", Lifecycle.ROLLOVER, ResetCost.FAST,
                Source.SELF, 1, this::liveDefinition, List.of(LIVE_ALIAS), true,
                "rollover 自动命名，不由代码 PUT");

        // L2 索引工厂（m11）
        index(DYN_CTRL, "m11", "m11-2", Lifecycle.RESET_ON_RUN, ResetCost.FAST,
                Source.SELF, DYN_DOCS, this::dynCtrlDefinition, List.of(), true,
                "裸 dynamic 对照：24 个属性各自成字段");
        index(DYN, "m11", "m11-2", Lifecycle.RESET_ON_RUN, ResetCost.FAST,
                Source.SELF, DYN_DOCS, this::dynDefinition, List.of(), true,
                "dynamic_templates：attrs 收成 1 个 flattened");
        index(TPL, "m11", "m11-1", Lifecycle.PERSISTENT, ResetCost.FAST,
                Source.SELF, 0, this::templateIndexDefinition, List.of(), true,
                "模板产物：空 body 建出来也能带 1 分片 / refresh 5s");
        index(TPL_PLANNED, "m11", "m11-1", Lifecycle.PLANNED, ResetCost.FAST,
                Source.SELF, 0, this::templateIndexDefinition, List.of(), true,
                "只做 _simulate_index，永不创建");
        index(INGEST, "m11", "m11-3", Lifecycle.PERSISTENT, ResetCost.FAST,
                Source.SELF, 1, this::ingestDefinition, List.of(), true,
                "pipeline 落库目标：1 条 INGEST-1");

        // m8-3 的失败分支与取消分支：都是一次性的，跑完即删
        index(REINDEX_FAIL, "m8", "m8-3", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 0, this::reindexFailDefinition, List.of(), true,
                "strict + 只有 sku：从 v4 搬过来时每条都会 mapping 失败");
        index(V5_CANCEL, "m8", "m8-3", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 0, this::v5Definition, List.of(), true,
                "限速后跑不完，用来演示超时与 _cancel");

        // M1/M3 的演示索引：每关自己重建，不跨关复用
        index(DEMO_AUTO, "m1", "m1-1", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 2, this::demoAutoDefinition, List.of(), true,
                "运行期间存在，展示完字段类型就删；mappings 交给动态映射");
        index(DEMO_EXPLODE, "m1", "m1-4", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 900, this::demoExplodeDefinition, List.of(), true,
                "运行期间存在：450 个属性会撑成 900+ 字段，演示完即删");
        index(DEMO_WRITE, "m3", "m3-1", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 200, this::demoWriteDefinition, List.of(), true,
                "运行期间存在：refresh=-1，单条写与 bulk 写各跑一遍");
        index(DEMO_ID_AUTO, "m3", "m3-2", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 3_000, this::demoIdDefinition, List.of(), true,
                "运行期间存在：自动 _id 的写入对照");
        index(DEMO_ID_BIZ, "m3", "m3-2", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 3_000, this::demoIdDefinition, List.of(), true,
                "运行期间存在：业务 _id 的写入对照");
        index(DEMO_REFRESH, "m3", "m3-3", Lifecycle.DROP_AFTER_RUN, ResetCost.FAST,
                Source.SELF, 1, this::demoRefreshOneSecond, List.of(), true,
                "运行期间存在：refresh=1s，写入后立刻查看不到");
        index(DEMO_REFRESH_ON, "m3", "exp-refresh-bulk", Lifecycle.DROP_AFTER_RUN, ResetCost.MEDIUM,
                Source.SELF, 20_000, this::demoRefreshOneSecond, List.of(), true,
                "实验 D 的 A 组：导入期开 1s 刷新，实验结束删");
        index(DEMO_REFRESH_OFF, "m3", "exp-refresh-bulk", Lifecycle.DROP_AFTER_RUN, ResetCost.MEDIUM,
                Source.SELF, 20_000, this::demoRefreshOff, List.of(), true,
                "实验 D 的 B 组：导入期关刷新，导完手动 refresh，实验结束删");

        // L4 集群资产
        alias(ALIAS, V3, List.of(V3, V4, V6));
        alias(LIVE_ALIAS, LIVE_2, List.of(LIVE_1, LIVE_2));
        asset("template", "eslab-goods-template", "eslab-goods-tpl-*", "m11", "m11-1");
        asset("pipeline", "eslab-goods-enrich", null, "m11", "m11-3");
    }

    private void index(String name, String chapter, String owner, Lifecycle lifecycle,
                       ResetCost cost, Source source, int target,
                       Supplier<Map<String, Object>> definition, List<String> aliasNames,
                       boolean labWritable, String note) {
        indices.put(name, new IndexSpec(name, chapter, owner, lifecycle, cost, source,
                target, definition, aliasNames, labWritable, note));
    }

    private void alias(String name, String defaultTarget, List<String> candidates) {
        aliases.put(name, new AliasSpec(name, defaultTarget, candidates));
    }

    private void asset(String type, String name, String pattern, String chapter, String owner) {
        assets.put(name, new AssetSpec(type, name, pattern, chapter, owner));
    }

    // ── 索引定义 ─────────────────────────────────────────────

    /** v3：与 v2 完全同构，只是换了个名字 —— 别名要指向它 */
    public Map<String, Object> v3Definition() {
        return copy(ds.v2Definition());
    }

    /**
     * v4：在 v2 之上做两处「不能原地改」的变更。
     *
     * 加字段只是表象；真正要讲的是 ignore_above 这类已有字段的参数同样改不了 ——
     * 很多人以为「只改一个参数」可以原地 PUT，实际一样要建新索引。
     */
    public Map<String, Object> v4Definition() {
        Map<String, Object> def = copy(ds.v2Definition());
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
        Map<String, Object> def = copy(ds.v2Definition());
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
     * 受控分片实验的索引定义：与 v2 完全同构（同 mapping、同 analyzer、同 doc_values），
     * 只把主分片数换成指定值。m7-1 用它把「分片数」这一个变量单独拎出来。
     */
    public Map<String, Object> shardLabDefinition(int shards) {
        Map<String, Object> def = copy(ds.v2Definition());
        obj(def.get("settings")).put("number_of_shards", shards);
        obj(def.get("settings")).put("number_of_replicas", 0);
        return def;
    }

    /** 滚动索引的初始索引：单分片，带写别名 */
    public Map<String, Object> liveDefinition() {
        Map<String, Object> def = copy(ds.v2Definition());
        obj(def.get("settings")).put("number_of_shards", 1);
        return def;
    }

    /** 建索引时就把写别名挂上，rollover 要求别名指向的索引是唯一的写目标 */
    public Map<String, Object> liveDefinitionWithAlias() {
        Map<String, Object> def = liveDefinition();
        def.put("aliases", Json.map(LIVE_ALIAS, Json.map("is_write_index", true)));
        return def;
    }

    /** m11-2 对照组：裸 dynamic，24 个属性各自成字段 */
    public Map<String, Object> dynCtrlDefinition() {
        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map(
                        "dynamic", true,
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text",
                                        "analyzer", "ik_max_word", "search_analyzer", "ik_smart"))));
    }

    /** m11-2 实验组：dynamic_templates 把 attrs 收成 1 个 flattened，字符串压成 keyword */
    public Map<String, Object> dynDefinition() {
        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map(
                        "dynamic", true,
                        "dynamic_templates", List.of(
                                Json.map("attrs_as_flattened", Json.map(
                                        "match", "attrs",
                                        "mapping", Json.map("type", "flattened"))),
                                Json.map("strings_as_keyword", Json.map(
                                        "match_mapping_type", "string",
                                        "mapping", Json.map("type", "keyword", "ignore_above", 256)))),
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text",
                                        "analyzer", "ik_max_word", "search_analyzer", "ik_smart"))));
    }

    /**
     * m11-1 的模板产物：空 body 建索引，settings/mappings 全部由 index_template 给。
     * 这里返回空 Map 是刻意的 —— 如果给它写死定义，这一关就演示不了模板的作用了。
     */
    public Map<String, Object> templateIndexDefinition() {
        return Json.map();
    }

    // ── M1/M3 演示索引的定义 ──────────────────────────────────

    /** m1-1：刻意不写 mappings —— 这一关要演示的就是「不写会怎样」 */
    public Map<String, Object> demoAutoDefinition() {
        return Json.map("settings", Json.map("number_of_shards", 1, "number_of_replicas", 0));
    }

    /** m1-4：允许多态映射，属性名由写入方决定 */
    public Map<String, Object> demoExplodeDefinition() {
        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map("dynamic", true));
    }

    /** m3-1：关掉刷新，让「单条 vs bulk」只差请求次数这一个变量 */
    public Map<String, Object> demoWriteDefinition() {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "refresh_interval", "-1"),
                "mappings", Json.map(
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "category", Json.map("type", "keyword"),
                                "tags", Json.map("type", "keyword"))));
    }

    /** m3-2：两个索引定义完全一致，唯一变量是 _id 由谁生成 */
    public Map<String, Object> demoIdDefinition() {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "refresh_interval", "-1"),
                "mappings", Json.map(
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100))));
    }

    /** m3-3 与实验 D 共用同一份字段表，只把 refresh_interval 当参数 */
    public Map<String, Object> demoRefreshDefinition(String refresh) {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "refresh_interval", refresh),
                "mappings", Json.map(
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text", "analyzer", "ik_max_word"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "category", Json.map("type", "keyword"),
                                "tags", Json.map("type", "keyword"))));
    }

    public Map<String, Object> demoRefreshOneSecond() {
        return demoRefreshDefinition("1s");
    }

    public Map<String, Object> demoRefreshOff() {
        return demoRefreshDefinition("-1");
    }

    /** m8-3 失败分支：只剩一个 sku 字段，strict 会拒掉 v4 的所有文档 */
    public Map<String, Object> reindexFailDefinition() {        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map(
                        "dynamic", "strict",
                        "properties", Json.map("sku", Json.map("type", "keyword"))));
    }

    /** m11-3：pipeline 落库目标，字段与处理器输出对齐 */
    public Map<String, Object> ingestDefinition() {
        return Json.map(
                "settings", Json.map("number_of_shards", 1, "number_of_replicas", 0),
                "mappings", Json.map(
                        "dynamic", true,
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map("type", "text",
                                        "analyzer", "ik_max_word", "search_analyzer", "ik_smart"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "price_with_tax", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "status", Json.map("type", "integer"),
                                "ingested_at", Json.map("type", "date"))));
    }

    // ── 查询 ────────────────────────────────────────────────

    public IndexSpec require(String name) {
        IndexSpec spec = indices.get(name);
        if (spec == null) {
            throw new IllegalArgumentException("未登记的索引：" + name + " —— 先在 LabIndexes 里登记");
        }
        return spec;
    }

    public boolean known(String name) {
        return indices.containsKey(name);
    }

    /** 取一份新的定义（每次都是独立副本，调用方可以随便改） */
    public Map<String, Object> definitionOf(String name) {
        return require(name).newDefinition();
    }

    public List<IndexSpec> all() {
        return List.copyOf(indices.values());
    }

    public List<IndexSpec> byChapter(String chapter) {
        List<IndexSpec> out = new ArrayList<>();
        for (IndexSpec s : indices.values()) {
            if (s.chapter().equals(chapter)) out.add(s);
        }
        return out;
    }

    public List<IndexSpec> byOwner(String levelId) {
        List<IndexSpec> out = new ArrayList<>();
        for (IndexSpec s : indices.values()) {
            if (s.ownerLevel().equals(levelId)) out.add(s);
        }
        return out;
    }

    public List<IndexSpec> byLifecycle(Lifecycle lifecycle) {
        List<IndexSpec> out = new ArrayList<>();
        for (IndexSpec s : indices.values()) {
            if (s.lifecycle() == lifecycle) out.add(s);
        }
        return out;
    }

    public Optional<AliasSpec> alias(String name) {
        return Optional.ofNullable(aliases.get(name));
    }

    public List<AliasSpec> aliases() {
        return List.copyOf(aliases.values());
    }

    public List<AssetSpec> assets() {
        return List.copyOf(assets.values());
    }

    // ── 校验 ────────────────────────────────────────────────

    /**
     * 登记不全就不要启动。这些错误在运行时表现为「某一关 404」，
     * 学员看到的是教学失败，我们看到的是一堆无关日志 —— 越早炸越好。
     */
    private void validate() {
        List<String> problems = new ArrayList<>();
        for (IndexSpec s : indices.values()) {
            String n = s.name();
            if (!n.startsWith("eslab-")) {
                problems.add(n + "：索引名必须以 eslab- 开头");
            }
            if (s.targetDocs() < 0) {
                problems.add(n + "：targetDocs 不能为负");
            }
            if (s.definition() == null) {
                problems.add(n + "：缺少索引定义");
            }
            if (s.source() != Source.SELF && s.source() != Source.REINDEX_ALIAS
                    && !indices.containsKey(SOURCE)) {
                problems.add(n + "：reindex 源 " + SOURCE + " 未登记");
            }
            for (String a : s.aliases()) {
                if (!aliases.containsKey(a)) {
                    problems.add(n + "：声明了未登记的别名 " + a);
                }
            }
        }
        for (AliasSpec a : aliases.values()) {
            if (!indices.containsKey(a.defaultTarget())) {
                problems.add(a.name() + "：默认目标 " + a.defaultTarget() + " 未登记");
            }
            for (String c : a.candidates()) {
                if (!indices.containsKey(c)) {
                    problems.add(a.name() + "：候选目标 " + c + " 未登记");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("LabIndexes 登记有误：\n  · " + String.join("\n  · ", problems));
        }
    }

    // ── 工具 ────────────────────────────────────────────────

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
