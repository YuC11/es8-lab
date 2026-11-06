package com.eslab.data;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.seed.Generator;
import com.eslab.seed.ProductDoc;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 实验数据集：两个索引的唯一定义处与唯一写入处。
 *
 * 为什么把这段从 seed 里抽出来：
 *   第 0 章要让学员「在页面里亲手把库建出来」，seed 只是同一条路径的批量快捷方式。
 *   定义、建索引、bulk、统计如果各写一份，教学页面和命令行脚本迟早会给出不同的数字。
 *
 * 两个索引的差别只在设计，不在数据：
 *   v1 = 全动态映射 + 1 分片（「不设计」的默认结果，也是后面所有坑的来源）
 *   v2 = 显式 Mapping + 3 分片 + ik（每一处类型都由「字段怎么被查」反推而来）
 */
@Service
public class DatasetService {

    public static final String PREFIX = "eslab-";
    public static final String V1 = "eslab-products-v1";
    public static final String V2 = "eslab-products-v2";

    /** 课程内构建的默认规模：2 万，十秒内能跑完，交互不被打断 */
    public static final int DEFAULT_TARGET = 20_000;
    /** 全量：M5 聚合误差、M6 深分页需要这个量级才有真实表现 */
    public static final int FULL_TARGET = 200_000;

    private final EsClient es;
    private final ObjectMapper mapper;

    public DatasetService(EsClient es, ObjectMapper mapper) {
        this.es = es;
        this.mapper = mapper;
    }

    // ── 索引定义 ─────────────────────────────────────────────

    /** v1：只给分片数，不给 mappings —— 字段类型交给 ES 猜 */
    public Map<String, Object> v1Definition() {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 1,
                        "number_of_replicas", 0,
                        "refresh_interval", "1s"));
    }

    /** v2：逐字段指定类型、分词器、是否需要 keyword 子字段 */
    public Map<String, Object> v2Definition() {
        return Json.map(
                "settings", Json.map(
                        "number_of_shards", 3,
                        "number_of_replicas", 0,
                        "refresh_interval", "1s"),
                "mappings", Json.map(
                        "dynamic", "strict",
                        "properties", Json.map(
                                "sku", Json.map("type", "keyword"),
                                "title", Json.map(
                                        "type", "text",
                                        "analyzer", "ik_max_word",
                                        "search_analyzer", "ik_smart",
                                        "fields", Json.map("keyword",
                                                Json.map("type", "keyword", "ignore_above", 256))),
                                "description", Json.map(
                                        "type", "text",
                                        "analyzer", "ik_max_word",
                                        "search_analyzer", "ik_smart"),
                                "category", Json.map("type", "keyword"),
                                "brand", Json.map("type", "keyword"),
                                "price", Json.map("type", "scaled_float", "scaling_factor", 100),
                                "stock", Json.map("type", "integer"),
                                "status", Json.map("type", "integer"),
                                "tags", Json.map("type", "keyword"),
                                "created_at", Json.map("type", "date"),
                                "rating", Json.map("type", "half_float"),
                                "sales", Json.map("type", "integer"),
                                "seller_id", Json.map("type", "keyword"),
                                "specs", Json.map("properties", Json.map(
                                        "color", Json.map("type", "keyword"),
                                        "material", Json.map("type", "keyword"),
                                        "weight_g", Json.map("type", "integer"))))));
    }

    public String indexOf(String version) {
        return switch (version) {
            case "v1" -> V1;
            case "v2" -> V2;
            default -> throw new IllegalArgumentException("未知版本：" + version);
        };
    }

    public void create(String version) {
        String index = indexOf(version);
        Map<String, Object> def = "v1".equals(version) ? v1Definition() : v2Definition();
        es.put("/" + index, def);
    }

    // ── 状态 ────────────────────────────────────────────────

    public boolean exists(String index) {
        try {
            es.call("HEAD", "/" + index, null, null, 5_000);
            return true;
        } catch (EsException e) {
            return false;
        }
    }

    public void deleteIfExists(String index) {
        if (!index.startsWith(PREFIX)) {
            throw new IllegalArgumentException("拒绝操作非 " + PREFIX + " 前缀索引：" + index);
        }
        if (!exists(index)) return;
        es.delete("/" + index);
    }

    public long count(String index) {
        try {
            return es.get("/" + index + "/_count").path("count").asLong(0);
        } catch (EsException e) {
            return -1;
        }
    }

    /** 供健康检查与路线图使用的单索引摘要；索引不存在时 exists=false */
    public Map<String, Object> describe(String index) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("index", index);
        boolean exists = exists(index);
        out.put("exists", exists);
        if (!exists) {
            out.put("docs", 0);
            out.put("shards", 0);
            out.put("storeBytes", 0);
            out.put("segments", 0);
            out.put("fields", 0);
            return out;
        }
        try {
            JsonNode row = es.get("/_cat/indices/" + index
                    + "?format=json&h=index,docs.count,store.size,segments.count,pri,rep").path(0);
            out.put("docs", row.path("docs.count").asLong(0));
            out.put("shards", row.path("pri").asInt(0));
            out.put("replicas", row.path("rep").asInt(0));
            out.put("storeBytes", parseSize(row.path("store.size").asText("0b")));
            out.put("segments", row.path("segments.count").asLong(0));
            JsonNode mapping = es.get("/" + index + "/_mapping").path(index)
                    .path("mappings").path("properties");
            out.put("fields", mapping.size());
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 能做 doc_values 聚合的数值/日期/布尔/keyword 类型 */
    private static final Set<String> AGG_TYPES = Set.of(
            "keyword", "integer", "long", "short", "byte",
            "float", "double", "scaled_float", "half_float", "date", "boolean", "ip");

    /** 字段用途的一句话说明：与 v2 的 mapping 一一对应，改 mapping 时这里要同步 */
    private static final Map<String, String> FIELD_ROLES = Map.ofEntries(
            Map.entry("sku", "业务主键：精确匹配、去重"),
            Map.entry("title", "商品标题：全文检索；排序/聚合要用 title.keyword"),
            Map.entry("description", "详情描述：只做全文检索，不做聚合"),
            Map.entry("category", "类目：等值过滤与分桶聚合"),
            Map.entry("brand", "品牌：等值过滤与分桶聚合"),
            Map.entry("price", "价格：范围过滤、求和、百分位"),
            Map.entry("stock", "库存：数值过滤"),
            Map.entry("status", "状态枚举：1 在售 / 2 缺货 / 3 预售 / 4 下架 / 5 清仓"),
            Map.entry("tags", "标签数组：多值等值匹配"),
            Map.entry("created_at", "上架时间：日期范围与按时间聚合"),
            Map.entry("rating", "评分：范围过滤"),
            Map.entry("sales", "销量：排序与求和"),
            Map.entry("seller_id", "卖家 ID：精确匹配"),
            Map.entry("specs", "规格对象：子字段 color / material / weight_g，跨字段关联需 nested"));

    /**
     * 字段字典：类型 + 是否可搜索/可聚合 + 一句用途。
     *
     * 速查页与控制台的字段提示都读这里，避免前端再抄一份字段表 ——
     * 类型从真实 mapping 读，所以 v1（动态）与 v2（显式）会如实反映各自的结果。
     */
    public List<Map<String, Object>> fieldDict(String index) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!exists(index)) return out;
        try {
            JsonNode props = es.get("/" + index + "/_mapping").path(index)
                    .path("mappings").path("properties");
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String name = e.getKey();
                JsonNode def = e.getValue();
                String type = def.path("type").asText("");
                if (type.isEmpty() && def.has("properties")) type = "object";

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name", name);
                row.put("type", type);
                row.put("searchable", !type.isEmpty());
                row.put("aggregatable", AGG_TYPES.contains(type));
                row.put("hasKeyword", def.has("fields") && def.path("fields").has("keyword"));
                row.put("role", FIELD_ROLES.getOrDefault(name, ""));
                out.add(row);
            }
        } catch (Exception e) {
            return out;
        }
        return out;
    }

    public static long parseSize(String s) {
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

    // ── 写入 ────────────────────────────────────────────────

    /** bulk 的实时进度：教学页面把它直接画成进度条 */
    public record BulkProgress(int written, int total, int rate, int batchIndex, int batches) {
    }

    public record BulkOutcome(int written, long ms, int rate, int batches,
                              long segments, long storeBytes, long docs) {
    }

    /**
     * 分批 bulk 写入。startFrom 用于「接着已有数据继续灌」。
     *
     * 两个真实细节：
     *   · 导入期间 refresh_interval=-1：否则每 1s 一次 refresh 要生成新段并可能触发 merge，
     *     20 万条的导入速度会被压到 1/3；
     *   · 每批 2000 条：批太大单请求超时，批太小网络往返占主导。
     */
    public BulkOutcome bulkLoad(String index, int total, int startFrom, Consumer<BulkProgress> listener)
            throws Exception {
        if (!exists(index)) {
            throw new IllegalStateException("索引 " + index + " 不存在，先创建它");
        }
        es.put("/" + index + "/_settings", Json.map("index", Json.map("refresh_interval", "-1")));

        int batchSize = 2_000;
        int batches = (int) Math.ceil(total / (double) batchSize);
        int done = 0;
        long started = System.currentTimeMillis();
        try {
            while (done < total) {
                int end = Math.min(done + batchSize, total);
                StringBuilder sb = new StringBuilder((end - done) * 600);
                for (int i = startFrom + done; i < startFrom + end; i++) {
                    ProductDoc doc = Generator.makeDoc(i, 42);
                    sb.append("{\"index\":{\"_index\":\"").append(index).append("\"}}\n");
                    sb.append(mapper.writeValueAsString(doc)).append('\n');
                }
                JsonNode res = es.bulk(sb.toString(), 180_000);
                if (res.path("errors").asBoolean(false)) {
                    throw new IllegalStateException("批量写入出现失败：" + res.path("items").path(0));
                }
                done = end;
                long elapsed = Math.max(1, System.currentTimeMillis() - started);
                int rate = (int) (done / (elapsed / 1000.0));
                if (listener != null) {
                    listener.accept(new BulkProgress(done, total, rate, (int) Math.ceil(done / (double) batchSize), batches));
                }
                System.out.printf("\r  %s: %d/%d (%d%%) %d docs/s   ",
                        index, done, total, done * 100 / total, rate);
            }
        } finally {
            es.put("/" + index + "/_settings", Json.map("index", Json.map("refresh_interval", "1s")));
            es.post("/" + index + "/_refresh", null);
        }
        long ms = System.currentTimeMillis() - started;
        // store.size 由分片统计异步更新：refresh 后立刻读会拿到旧值（往往是几百字节），
        // 等一个统计周期再读，页面上的体积才是真实的
        Thread.sleep(1_200);
        long docs = count(index);
        Map<String, Object> st = describe(index);
        System.out.println();
        return new BulkOutcome(done, ms, (int) (done / Math.max(ms / 1000.0, 0.001)), batches,
                ((Number) st.getOrDefault("segments", 0)).longValue(),
                ((Number) st.getOrDefault("storeBytes", 0)).longValue(),
                docs);
    }

    /** 进度回调用的空实现，避免调用处到处判空 */
    public static Consumer<BulkProgress> noop() {
        return p -> {
        };
    }

    public void refresh(String index) {
        es.post("/" + index + "/_refresh", null);
    }

    /**
     * 全部 eslab-* 索引的实时状态，供前置检查识别「非 v1/v2」的实验索引。
     *
     * 一条 _cat 请求拿全，而不是每个索引调一次 describe：新章节会新增 6-8 个索引，
     * 逐个查会让 /api/data 变成 N+1。
     */
    public List<Map<String, Object>> listDetailed() {
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/indices/" + PREFIX
                    + "*?format=json&h=index,health,pri,rep,docs.count,store.size,segments.count");
            for (JsonNode r : arr) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("index", r.path("index").asText());
                row.put("exists", true);
                row.put("health", r.path("health").asText());
                row.put("docs", r.path("docs.count").asLong(0));
                row.put("shards", r.path("pri").asInt(0));
                row.put("replicas", r.path("rep").asInt(0));
                row.put("storeBytes", parseSize(r.path("store.size").asText("0b")));
                row.put("segments", r.path("segments.count").asLong(0));
                row.put("fields", 0);
                rows.add(row);
            }
        } catch (Exception ignored) {
            // ES 未启动时返回空列表
        }
        return rows;
    }

    /** 别名 → 目标索引。前置检查要靠它判断 eslab-goods 是否可用 */
    public List<Map<String, Object>> listAliases() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/aliases/" + PREFIX
                    + "*?format=json&h=alias,index,is_write_index");
            Map<String, List<String>> targets = new LinkedHashMap<>();
            Map<String, String> writeTarget = new LinkedHashMap<>();
            for (JsonNode r : arr) {
                String alias = r.path("alias").asText();
                String index = r.path("index").asText();
                targets.computeIfAbsent(alias, k -> new ArrayList<>()).add(index);
                if ("true".equals(r.path("is_write_index").asText())) writeTarget.put(alias, index);
            }
            for (Map.Entry<String, List<String>> e : targets.entrySet()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("alias", e.getKey());
                row.put("index", e.getKey());
                row.put("exists", true);
                row.put("targets", e.getValue());
                row.put("writeIndex", writeTarget.getOrDefault(e.getKey(), e.getValue().get(0)));
                row.put("docs", count(e.getKey()));
                out.add(row);
            }
        } catch (Exception ignored) {
            // 没有别名时 _cat/aliases 返回空数组，不是错误
        }
        return out;
    }

    public List<String> indices() {
        List<String> out = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/indices/" + PREFIX + "*?format=json&h=index");
            for (JsonNode r : arr) out.add(r.path("index").asText());
        } catch (Exception ignored) {
            // ES 未启动时返回空
        }
        return out;
    }
}
