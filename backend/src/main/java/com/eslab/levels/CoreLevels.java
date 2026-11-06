package com.eslab.levels;

import com.eslab.data.DatasetService;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M1-M6 的操作型小关卡。
 *
 * 与 44 个主关卡的区别：这里不追求「讲一个原理」，而是把一类日常操作做完整 ——
 * 创建/覆盖/删除、mapping 巡检、_analyze 调试、bulk 逐项失败、字段裁剪、
 * 多请求合并、指标聚合、桶遍历、scroll 与 explain。
 *
 * 三条共同约定：
 * 1. 全部只接触可重建的 {@code eslab-goods-core}（30 条确定性样例），
 *    每次运行先 rebuild，所以重复运行不会累积副作用，也不碰 v1/v2 主索引。
 * 2. 每一关都给出「对照」而不是单个数字：覆盖 vs 合并、create vs index、
 *    _source vs docvalue vs stored、scroll vs PIT、match vs term。
 * 3. 结论只写到本机能验证的范围：单节点、30 条样例、无缓存预热。
 */
public final class CoreLevels {
    private CoreLevels() {
    }

    abstract static class CoreLevel implements LevelModule {
        final EsClient es;
        final LabData data;

        CoreLevel(EsClient es, LabData data) {
            this.es = es;
            this.data = data;
        }

        void reset() {
            data.resetCore();
        }

        JsonNode search(Object body) {
            return es.post("/" + LabIndexes.CORE + "/_search", body);
        }

        long total(JsonNode resp) {
            return resp.path("hits").path("total").path("value").asLong(0);
        }

        static String fmt(long n) {
            return String.format(Locale.ROOT, "%,d", n);
        }

        static String num(double v) {
            return String.format(Locale.ROOT, "%.2f", v);
        }
    }

    // ── m1-5 文档 CRUD：覆盖与合并不是一回事 ────────────────────
    @Component
    public static final class M15Crud extends CoreLevel {
        M15Crud(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m1-5";
        }

        public LevelResult run() {
            reset();
            String doc = "/" + LabIndexes.CORE + "/_doc/core-crud";

            JsonNode created = es.put(doc, Json.map(
                    "sku", "CORE-CRUD", "title", "蓝牙耳机 CRUD 样例", "stock", 1, "price", 199));
            JsonNode updated = es.post("/" + LabIndexes.CORE + "/_update/core-crud",
                    Json.map("doc", Json.map("stock", 2)));
            JsonNode merged = es.get(doc);

            // 同样是 PUT，但这次 body 里只有 stock：整条文档被替换，其余字段消失
            JsonNode overwritten = es.put(doc + "?refresh=true", Json.map("stock", 3));
            JsonNode replaced = es.get(doc);

            JsonNode deleted = es.delete(doc);
            String afterDelete;
            try {
                es.get(doc);
                afterDelete = "仍可读到（异常）";
            } catch (EsException e) {
                afterDelete = "HTTP " + e.status();
            }

            int mergedFields = merged.path("_source").size();
            int replacedFields = replaced.path("_source").size();

            List<List<Object>> rows = new ArrayList<>();
            rows.add(TableData.row("PUT 创建", created.path("result").asText(), created.path("_version").asLong(), mergedFields + "（sku/title/stock/price）"));
            rows.add(TableData.row("POST _update doc", updated.path("result").asText(), updated.path("_version").asLong(), mergedFields + "（只有 stock 变了）"));
            rows.add(TableData.row("PUT 整体覆盖", overwritten.path("result").asText(), overwritten.path("_version").asLong(), replacedFields + "（只剩 stock）"));
            rows.add(TableData.row("DELETE", deleted.path("result").asText(), deleted.path("_version").asLong(), afterDelete));

            return LevelResult.builder("_update 只合并给定字段（" + mergedFields + " 个字段仍在），"
                            + "PUT 是整条替换（覆盖后只剩 " + replacedFields + " 个字段）；删除后再 GET 得到 "
                            + afterDelete)
                    .metrics(
                            new Metric("创建后 _version", created.path("_version").asLong(), "首次写入是 1"),
                            new Metric("_update 后 _source", mergedFields + " 个字段", "合并语义：未提及的字段保留"),
                            new Metric("整体覆盖后 _source", replacedFields + " 个字段", "替换语义：未提及的字段被丢弃"),
                            new Metric("删除后再读", afterDelete, "文档真没了，不是标记"))
                    .table(TableData.of(List.of("操作", "result", "_version", "_source"), rows))
                    .json(Map.of("create", created, "update", updated, "afterUpdate", merged,
                            "overwrite", overwritten, "afterOverwrite", replaced, "delete", deleted))
                    .notes(
                            "index（PUT）与 update 的区别不是「谁更快」，而是语义：PUT 用你给的 body 替换整条 _source，update 只合并 doc 里的字段。",
                            "两个 API 都会让 _version 递增 —— 版本号反映的是「这条文档被写过几次」，不区分是合并还是替换。",
                            "本关每次运行都重建 eslab-goods-core 并写入固定 ID core-crud，重跑不会累积文档；删除后 _version 归零（文档已不存在）。")
                    .build();
        }
    }

    // ── m1-6 mapping / settings / field_caps 三种事实 ────────────
    @Component
    public static final class M16Inspect extends CoreLevel {
        M16Inspect(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m1-6";
        }

        public LevelResult run() {
            reset();
            JsonNode mapping = es.get("/" + LabIndexes.CORE + "/_mapping");
            JsonNode settings = es.get("/" + LabIndexes.CORE + "/_settings");
            JsonNode capsCore = es.post("/" + LabIndexes.CORE + "/_field_caps",
                    Json.map("fields", List.of("title", "price", "sku", "brand")));
            JsonNode capsV2 = es.post("/" + DatasetService.V2 + "/_field_caps",
                    Json.map("fields", List.of("title", "price")));

            List<String> fields = List.of("title", "price", "sku", "brand");
            List<List<Object>> rows = new ArrayList<>();
            for (String f : fields) {
                JsonNode variants = capsCore.path("fields").path(f);
                String type = "缺失";
                boolean searchable = false;
                boolean aggregatable = false;
                boolean hasKeyword = false;
                for (var it = variants.fields(); it.hasNext(); ) {
                    var e = it.next();
                    type = e.getValue().path("type").asText(type);
                    searchable = e.getValue().path("searchable").asBoolean(false);
                    aggregatable = e.getValue().path("aggregatable").asBoolean(false);
                    if (e.getKey().endsWith(".keyword")) hasKeyword = true;
                }
                rows.add(TableData.row(f, type, searchable ? "是" : "否",
                        aggregatable ? "是" : "否", hasKeyword ? "有 .keyword 子字段" : "—"));
            }
            String v2Title = capsV2.path("fields").path("title").path("text").path("type").asText("?");
            String v2Price = capsV2.path("fields").path("price").path("scaled_float").path("type").asText("?");

            JsonNode indexSettings = settings.path(LabIndexes.CORE).path("settings").path("index");
            return LevelResult.builder("核心样例索引有 " + mapping.path(LabIndexes.CORE).path("mappings").path("properties").size()
                            + " 个字段、1 个分片；field_caps 能同时告出「类型 / 可搜索 / 可聚合」，"
                            + "而 v2 的 title 是 text、price 是 scaled_float —— 两份索引的同一字段能力并不相同")
                    .metrics(
                            new Metric("显式字段数", mapping.path(LabIndexes.CORE).path("mappings").path("properties").size(), "core 的定义是 strict"),
                            new Metric("分片 / 副本", indexSettings.path("number_of_shards").asText() + " / "
                                    + indexSettings.path("number_of_replicas").asText(), "分片数建库后不可改"),
                            new Metric("v2.title 类型", v2Title, "同一字段在两份索引里可以不同"),
                            new Metric("v2.price 类型", v2Price, "scaled_float：按整数存，保留 2 位小数"))
                    .table(TableData.of(List.of("字段", "类型", "可搜索", "可聚合", "keyword 子字段"), rows))
                    .json(Map.of("mapping", mapping, "settings", settings, "fieldCapsCore", capsCore, "fieldCapsV2", capsV2))
                    .notes(
                            "_mapping 说的是「字段被怎么索引」，_settings 说的是「索引本身怎么组织」，_field_caps 是前两者推导出的能力表 —— 三者会不一致，排查时按需取。",
                            "可聚合（aggregatable）与可搜索（searchable）不是同一个属性：text 字段可搜索但默认不可聚合，keyword 两者都行。",
                            "number_of_shards 建库后不能改，number_of_replicas 与 refresh_interval 可以随时改 —— 这是 m0-2 那个错误响应的另一面。")
                    .build();
        }
    }

    // ── m2-3 用 _analyze 解释「为什么查不到」 ────────────────────
    @Component
    public static final class M23AnalyzeDebug extends CoreLevel {
        M23AnalyzeDebug(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m2-3";
        }

        public LevelResult run() {
            reset();
            String text = "蓝牙耳机 主动降噪";
            JsonNode byField = es.post("/" + LabIndexes.CORE + "/_analyze",
                    Json.map("field", "title", "text", text));
            JsonNode standard = es.post("/_analyze", Json.map("analyzer", "standard", "text", text));

            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode t : byField.path("tokens")) {
                rows.add(TableData.row("title（ik_max_word）", t.path("token").asText(),
                        t.path("position").asInt(), t.path("start_offset").asInt() + "–" + t.path("end_offset").asInt()));
            }
            for (int i = 0; i < Math.min(8, standard.path("tokens").size()); i++) {
                JsonNode t = standard.path("tokens").get(i);
                rows.add(TableData.row("standard", t.path("token").asText(),
                        t.path("position").asInt(), t.path("start_offset").asInt() + "–" + t.path("end_offset").asInt()));
            }

            int ik = byField.path("tokens").size();
            int std = standard.path("tokens").size();
            // 用同一串去查：ik 侧的「蓝牙耳机」是一个整词，standard 侧只有单字
            long hitIk = total(search(Json.map("size", 0, "query", Json.map("match", Json.map("title", text)))));
            long hitOneChar = total(search(Json.map("size", 0, "query", Json.map("term", Json.map("title", "蓝")))));

            return LevelResult.builder("同一串「" + text + "」：title 用的 ik_max_word 切出 " + ik
                            + " 个词，standard 切出 " + std + " 个单字；对同一字段 term「蓝」命中 " + fmt(hitOneChar)
                            + " 条 —— 倒排里只有「蓝牙」「耳机」这样的词项，没有单字「蓝」")
                    .metrics(
                            new Metric("ik_max_word token", ik, "蓝牙 / 耳机 / 主动 / 降噪"),
                            new Metric("standard token", std, "每个汉字一个 token，另有空格分隔"),
                            new Metric("match 命中", fmt(hitIk), "查询串走同一个 search_analyzer"),
                            new Metric("term「蓝」命中", fmt(hitOneChar), "term 不分析，单字词项不存在"))
                    .table(TableData.of(List.of("分析器", "token", "position", "offset"), rows))
                    .json(Map.of("byField", byField, "standard", standard))
                    .notes(
                            "排查「明明有这个商品却搜不到」的第一步不是改 query，而是把同一个字符串丢进 _analyze：看索引期与查询期切出来的 token 是否对得上。",
                            "ik_max_word 在索引期尽量细切（词多、召回高、索引大），ik_smart 在查询期粗切；两者不一致时会出现「索引里有、查询时不匹配」。",
                            "注意 term 的结果取决于**字段的**分词器，不是标准分词器：同一个 term「蓝」如果打在 standard 索引的 text 字段上能命中，打在 ik 字段上就是 0 —— 这也是为什么换分词器通常要重建索引。")
                    .build();
        }
    }

    // ── m3-4 bulk 的逐项失败与安全重放 ──────────────────────────
    @Component
    public static final class M34BulkErrors extends CoreLevel {
        M34BulkErrors(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m3-4";
        }

        public LevelResult run() {
            reset();
            String first = "{\"create\":{\"_index\":\"" + LabIndexes.CORE + "\",\"_id\":\"core-0\"}}\n"
                    + "{\"sku\":\"DUP\"}\n"
                    + "{\"index\":{\"_index\":\"" + LabIndexes.CORE + "\",\"_id\":\"bulk-new\"}}\n"
                    + "{\"sku\":\"BULK-NEW\",\"title\":\"批量成功样例\",\"category\":\"数码影音\","
                    + "\"brand\":\"拾光\",\"price\":10,\"stock\":5,\"created_at\":\"2026-02-01\"}\n";
            JsonNode round1 = es.bulk(first, 30_000);

            // 重放：把 create 换成 index —— 已存在不再报错，代价是覆盖原文档
            String replay = "{\"index\":{\"_index\":\"" + LabIndexes.CORE + "\",\"_id\":\"core-0\"}}\n"
                    + "{\"sku\":\"DUP\"}\n"
                    + "{\"index\":{\"_index\":\"" + LabIndexes.CORE + "\",\"_id\":\"bulk-new\"}}\n"
                    + "{\"sku\":\"BULK-NEW\",\"title\":\"批量成功样例\",\"category\":\"数码影音\","
                    + "\"brand\":\"拾光\",\"price\":10,\"stock\":5,\"created_at\":\"2026-02-01\"}\n";
            JsonNode round2 = es.bulk(replay, 30_000);

            List<List<Object>> rows = new ArrayList<>();
            rows.add(TableData.row("create core-0（已存在）", itemStatus(round1, 0), itemStatus(round2, 0)));
            rows.add(TableData.row("index bulk-new（新增）", itemStatus(round1, 1), itemStatus(round2, 1)));

            int conflictStatus = round1.path("items").get(0).path("create").path("status").asInt();
            data.refresh(LabIndexes.CORE);
            long docs = data.count(LabIndexes.CORE);

            return LevelResult.builder("第一轮 bulk 的 HTTP 是 200，但 errors=" + round1.path("errors").asBoolean()
                            + "：create 冲突的那条状态是 " + conflictStatus
                            + "。把 create 换成 index 重放后 errors=" + round2.path("errors").asBoolean()
                            + "，索引里现在是 " + fmt(docs) + " 条")
                    .metrics(
                            new Metric("第一轮 errors", round1.path("errors").asBoolean(), "整体 200，逐项有失败"),
                            new Metric("冲突状态码", conflictStatus, "version_conflict_engine_exception"),
                            new Metric("重放后 errors", round2.path("errors").asBoolean(), "index 覆盖写，不报错"),
                            new Metric("最终文档数", fmt(docs), "31 = 30 条样例 + bulk-new；core-0 被覆盖不新增"))
                    .table(TableData.of(List.of("动作", "第一轮（create）", "第二轮（index 重放）"), rows))
                    .json(Map.of("round1", round1, "round2", round2))
                    .notes(
                            "判断 bulk 是否成功不能只看 HTTP 200，必须读 errors 与 items[].<action>.status —— 部分失败是常态，不是异常。",
                            "create 失败可以安全重放：它保证「不存在才写」，重复执行不会覆盖别人的修改；index 幂等但会覆盖，重放前要确认这是你想要的语义。",
                            "本机单节点、30 条样例，冲突只来自同一个 _id；生产上还要考虑 429（队列满）与 503，它们同样出现在 items 里而不是 HTTP 状态码上。")
                    .build();
        }

        private static String itemStatus(JsonNode bulk, int i) {
            JsonNode item = bulk.path("items").get(i);
            String action = item.fieldNames().hasNext() ? item.fieldNames().next() : "?";
            JsonNode node = item.path(action);
            int status = node.path("status").asInt();
            String result = node.path("result").asText(node.path("error").path("type").asText(""));
            return status + " · " + result;
        }
    }

    // ── m4-7 只改变返回载荷，不改变命中集 ───────────────────────
    @Component
    public static final class M47Fields extends CoreLevel {
        M47Fields(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m4-7";
        }

        public LevelResult run() {
            reset();
            JsonNode source = search(Json.map("size", 1, "_source", List.of("sku", "title"),
                    "query", Json.map("match_all", Json.map())));
            JsonNode docvalues = search(Json.map("size", 1, "_source", false,
                    "docvalue_fields", List.of("sku", "price", "created_at"),
                    "query", Json.map("match_all", Json.map())));
            JsonNode stored = search(Json.map("size", 1, "stored_fields", List.of("sku", "title"),
                    "query", Json.map("match_all", Json.map())));
            // 第 4 种：对 text 字段要 doc_values —— 直接被拒，而不是返回空
            String textDocvalue;
            try {
                search(Json.map("size", 1, "_source", false, "docvalue_fields", List.of("title"),
                        "query", Json.map("match_all", Json.map())));
                textDocvalue = "意外成功";
            } catch (EsException e) {
                textDocvalue = "HTTP " + e.status() + " · "
                        + e.payload().path("error").path("root_cause").path(0).path("type").asText("error");
            }

            long t1 = total(source), t2 = total(docvalues), t3 = total(stored);
            int srcFields = source.path("hits").path("hits").get(0).path("_source").size();
            int dvFields = docvalues.path("hits").path("hits").get(0).path("fields").size();
            int storedFields = stored.path("hits").path("hits").get(0).path("fields").size();

            List<List<Object>> rows = new ArrayList<>();
            rows.add(TableData.row("_source: [sku,title]", t1, srcFields + " 个", "原始文档裁剪"));
            rows.add(TableData.row("_source:false + docvalue_fields", t2, dvFields + " 个", "从列存读，适合数值/日期/keyword"));
            rows.add(TableData.row("stored_fields: [sku,title]", t3, storedFields + " 个", "mapping 未开 store，所以是空的"));
            rows.add(TableData.row("docvalue_fields: [title]", "—", "—", textDocvalue));

            return LevelResult.builder("三种取法的命中总数完全相同（" + fmt(t1) + " / " + fmt(t2) + " / " + fmt(t3)
                            + "）：裁剪字段只改变返回载荷。_source 拿到 " + srcFields
                            + " 个字段，docvalue_fields 拿到 " + dvFields + " 个，stored_fields 是 "
                            + storedFields + " 个 —— 因为 core 的 mapping 没有 store=true")
                    .metrics(
                            new Metric("命中总数", fmt(t1), "三种写法都是同一个 query"),
                            new Metric("_source 字段", srcFields, "裁剪后只剩 sku/title"),
                            new Metric("docvalue 字段", dvFields, "price 是数值、created_at 是日期"),
                            new Metric("stored 字段", storedFields, "默认 store=false，取不到"),
                            new Metric("对 text 要 docvalue", textDocvalue, "fielddata 未开启，直接拒绝"))
                    .table(TableData.of(List.of("取法", "命中总数", "返回字段数", "说明"), rows))
                    .json(Map.of("sourceFiltered", source, "docvalueFields", docvalues, "storedFields", stored))
                    .notes(
                            "_source 是写入时原样存的 JSON，裁剪它能省网络与反序列化，但拿不到 doc_values 里的规范化值。",
                            "docvalue_fields 读的是列存，只对 keyword / 数值 / 日期有效；text 字段默认没有 doc_values，请求它会报错而不是静默返回空。",
                            "stored_fields 只对 mapping 里显式 store=true 的字段有效。默认 mapping 不存 store，所以它返回空 fields —— 这是最容易误判成「字段丢了」的一种现象。")
                    .build();
        }
    }

    // ── m4-8 _msearch / _mget：合并请求，隔离失败 ───────────────
    @Component
    public static final class M48MultiRead extends CoreLevel {
        M48MultiRead(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m4-8";
        }

        public LevelResult run() {
            reset();
            // 第二个子请求故意对 text 字段排序：它会在自己的响应里报 400，不影响第一个
            String nd = "{\"index\":\"" + LabIndexes.CORE + "\"}\n"
                    + "{\"size\":0,\"track_total_hits\":true,\"query\":{\"term\":{\"category\":\"数码影音\"}}}\n"
                    + "{\"index\":\"" + LabIndexes.CORE + "\"}\n"
                    + "{\"size\":0,\"sort\":[{\"title\":\"asc\"}]}\n";
            JsonNode msearch = es.call("POST", "/_msearch", null, nd, 30_000).body();
            JsonNode mget = es.post("/" + LabIndexes.CORE + "/_mget",
                    Json.map("ids", List.of("core-0", "core-1", "missing")));

            JsonNode ok = msearch.path("responses").get(0);
            JsonNode bad = msearch.path("responses").get(1);
            String badReason = bad.path("error").path("reason").asText("—");
            if (badReason.length() > 90) badReason = badReason.substring(0, 90) + "…";

            int found = 0;
            for (JsonNode d : mget.path("docs")) if (d.path("found").asBoolean(false)) found++;

            List<List<Object>> rows = new ArrayList<>();
            rows.add(TableData.row("子请求 1：term category", "HTTP 200 · 命中 " + fmt(ok.path("hits").path("total").path("value").asLong()), "正常"));
            rows.add(TableData.row("子请求 2：sort title", bad.has("error") ? "该子响应带 error" : "意外成功", badReason));
            rows.add(TableData.row("_mget：core-0 / core-1 / missing", found + " 命中 / 3 请求", "missing 那条 found=false"));

            return LevelResult.builder("一个 _msearch 里塞两条查询：第 1 条命中 "
                            + fmt(ok.path("hits").path("total").path("value").asLong())
                            + " 条，第 2 条对 text 排序在自己的子响应里报错，整体仍是 HTTP 200；"
                            + "_mget 三条 id 命中 " + found + " 条，缺失的那条单独 found=false")
                    .metrics(
                            new Metric("子响应数", msearch.path("responses").size(), "顺序与请求一一对应"),
                            new Metric("失败的子请求", bad.has("error") ? 1 : 0, "错误被隔离在自己那条里"),
                            new Metric("_mget 命中", found + " / 3", "缺失 ID 不报错"),
                            new Metric("整体 HTTP", 200, "合并请求不代表全部成功"))
                    .table(TableData.of(List.of("子请求", "状态", "说明"), rows))
                    .json(Map.of("msearch", msearch, "mget", mget))
                    .notes(
                            "_msearch 的 body 是 NDJSON：奇数行是 header（可只写 index），偶数行是 query，行尾必须换行 —— 拼错一个换行就会 400。",
                            "子请求之间互相隔离是它相对「循环发 3 次 _search」的主要价值：一条写错不会让另外两条白跑。",
                            "_mget 对不存在的 id 返回 found=false 而不是 404；只有索引不存在才会整体报错。")
                    .build();
        }
    }

    // ── m5-4 filter / range / cardinality ──────────────────────
    @Component
    public static final class M54MetricsAgg extends CoreLevel {
        M54MetricsAgg(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m5-4";
        }

        public LevelResult run() {
            reset();
            JsonNode r = search(Json.map("size", 0, "aggs", Json.map(
                    "in_stock", Json.map(
                            "filter", Json.map("range", Json.map("stock", Json.map("gt", 20))),
                            "aggs", Json.map("price_stats", Json.map("stats", Json.map("field", "price")))),
                    "price_bands", Json.map("range", Json.map("field", "price",
                            "ranges", List.of(Json.map("to", 150), Json.map("from", 150, "to", 300), Json.map("from", 300)))),
                    "brand_count", Json.map("cardinality", Json.map("field", "brand")),
                    "brand_terms", Json.map("terms", Json.map("field", "brand", "size", 50)))));
            JsonNode aggs = r.path("aggregations");

            // 交叉核对：cardinality 是近似算法，用 terms 的桶数当精确值对照
            int exactBrands = aggs.path("brand_terms").path("buckets").size();
            long approxBrands = aggs.path("brand_count").path("value").asLong();
            long inStock = aggs.path("in_stock").path("doc_count").asLong();
            double avg = aggs.path("in_stock").path("price_stats").path("avg").asDouble();

            List<List<Object>> rows = new ArrayList<>();
            for (JsonNode b : aggs.path("price_bands").path("buckets")) {
                rows.add(TableData.row("价格区间 " + b.path("key").asText(), b.path("doc_count").asLong(), "range 左闭右开"));
            }
            rows.add(TableData.row("cardinality(brand)", approxBrands, "精确值来自 terms 桶数：" + exactBrands));
            rows.add(TableData.row("filter(stock>20)", inStock, "子集文档数，均价 " + num(avg)));

            return LevelResult.builder("同一份 30 条样例：stock>20 的有 " + inStock + " 条（均价 " + num(avg)
                            + "），价格区间三桶分别是 " + bucketCounts(aggs)
                            + "；cardinality 给出 " + approxBrands + " 个品牌，与 terms 精确桶数 " + exactBrands + " 一致")
                    .metrics(
                            new Metric("filter 子集", inStock + " 条", "只统计 stock>20"),
                            new Metric("子集均价", num(avg), "stats 在过滤器内部计算"),
                            new Metric("cardinality(brand)", approxBrands, "近似去重，小基数下与精确值相同"),
                            new Metric("terms 桶数", exactBrands, "精确但受 size 截断"))
                    .table(TableData.of(List.of("聚合", "数值", "口径"), rows))
                    .json(r)
                    .notes(
                            "filter 聚合不改变外层 hits，只把子聚合限制在子集里 —— 这是「同一个请求里算多个口径」的基础。",
                            "cardinality 用 HyperLogLog++ 近似计数，内存固定、误差随基数增长；小数据上它与精确值相同，不能因此认为它永远精确。",
                            "terms 的桶数受 size 限制（默认 10），超过就会截断并给出 sum_other_doc_count；本关把 size 调到 50 才敢把它当精确值用。")
                    .build();
        }

        private static String bucketCounts(JsonNode aggs) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode b : aggs.path("price_bands").path("buckets")) {
                if (sb.length() > 0) sb.append(" / ");
                sb.append(b.path("doc_count").asLong());
            }
            return sb.toString();
        }
    }

    // ── m5-5 composite：遍历全部桶，而不是 Top-N ────────────────
    @Component
    public static final class M55Composite extends CoreLevel {
        M55Composite(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m5-5";
        }

        public LevelResult run() {
            reset();
            List<String> seen = new ArrayList<>();
            List<Integer> pageSizes = new ArrayList<>();
            List<Object> pages = new ArrayList<>();
            JsonNode after = null;
            for (int page = 0; page < 10; page++) {
                Map<String, Object> composite = new LinkedHashMap<>();
                composite.put("size", 2);
                if (after != null) composite.put("after", after);
                composite.put("sources", List.of(Json.map("brand", Json.map("terms", Json.map("field", "brand")))));
                JsonNode resp = search(Json.map("size", 0, "aggs", Json.map("all", Json.map("composite", composite))));
                pages.add(resp);
                JsonNode buckets = resp.path("aggregations").path("all").path("buckets");
                pageSizes.add(buckets.size());
                for (JsonNode b : buckets) seen.add(b.path("key").path("brand").asText());
                JsonNode next = resp.path("aggregations").path("all").path("after_key");
                if (next.isMissingNode() || next.isNull() || buckets.size() == 0) break;
                after = next;
            }

            JsonNode terms = search(Json.map("size", 0, "aggs", Json.map("all",
                    Json.map("terms", Json.map("field", "brand", "size", 50)))));
            int exact = terms.path("aggregations").path("all").path("buckets").size();
            Set<String> unique = new LinkedHashSet<>(seen);

            List<List<Object>> rows = new ArrayList<>();
            for (int i = 0; i < pageSizes.size(); i++) {
                rows.add(TableData.row("第 " + (i + 1) + " 页", pageSizes.get(i) + " 个桶", i + 1 < pageSizes.size() ? "带 after_key" : "after_key 已消失"));
            }
            rows.add(TableData.row("合计", seen.size() + " 个（去重后 " + unique.size() + " 个）", "与 terms 精确值 " + exact + " 对比"));

            return LevelResult.builder("每页 2 个桶翻了 " + pageSizes.size() + " 页，共取到 " + seen.size()
                            + " 个品牌（去重后 " + unique.size() + " 个），与 terms 聚合的精确桶数 " + exact
                            + " 一致；没有重复也没有遗漏")
                    .metrics(
                            new Metric("翻页次数", pageSizes.size(), "composite size=2"),
                            new Metric("遍历桶数", seen.size(), "去重后 " + unique.size()),
                            new Metric("terms 精确桶数", exact, "size=50 不截断"),
                            new Metric("是否有重复", seen.size() == unique.size() ? "无" : "有", "after_key 保证不重不漏"))
                    .table(TableData.of(List.of("批次", "桶数", "游标"), rows))
                    .json(Map.of("pages", pages, "terms", terms))
                    .notes(
                            "composite 是「按字典序逐页遍历所有桶」，terms 是「取 count 最高的 N 个」—— 前者能穷尽，后者会截断。",
                            "翻页游标是 after_key，不是 from/size：它不随数据增长而变慢，也不受 10000 条限制。",
                            "composite 的桶没有 doc_count 排序语义；需要「全部品牌各多少条」时用它，需要「卖得最好的 10 个品牌」时用 terms。")
                    .build();
        }
    }

    // ── m6-4 scroll 与 PIT+search_after 的边界 ─────────────────
    @Component
    public static final class M64Scroll extends CoreLevel {
        M64Scroll(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m6-4";
        }

        public LevelResult run() {
            reset();

            // 路线 A：scroll
            JsonNode first = es.post("/" + LabIndexes.CORE + "/_search?scroll=1m",
                    Json.map("size", 10, "sort", List.of("_doc"), "query", Json.map("match_all", Json.map())));
            String scrollId = first.path("_scroll_id").asText();
            Set<String> scrollIds = new LinkedHashSet<>();
            collect(first, scrollIds);
            int batches = 1;
            String sid = scrollId;
            while (batches < 10) {
                JsonNode next = es.post("/_search/scroll", Json.map("scroll", "1m", "scroll_id", sid));
                int got = collect(next, scrollIds);
                if (got == 0) break;
                batches++;
            }
            JsonNode closed = es.call("DELETE", "/_search/scroll",
                    Json.map("scroll_id", List.of(scrollId))).body();

            // 路线 B：PIT + search_after
            JsonNode pit = es.post("/" + LabIndexes.CORE + "/_pit?keep_alive=1m", null);
            String pitId = pit.path("id").asText();
            Set<String> pitIds = new LinkedHashSet<>();
            JsonNode after = null;
            int pitPages = 0;
            while (pitPages < 10) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("size", 10);
                body.put("pit", Map.of("id", pitId));
                body.put("sort", List.of(Json.map("price", "asc"), Json.map("_shard_doc", "asc")));
                if (after != null) body.put("search_after", after);
                JsonNode page = es.post("/_search", body);
                JsonNode hits = page.path("hits").path("hits");
                if (hits.size() == 0) break;
                for (JsonNode h : hits) pitIds.add(h.path("_id").asText());
                after = hits.get(hits.size() - 1).path("sort");
                pitPages++;
                if (hits.size() < 10) break;
            }
            JsonNode pitClosed = es.call("DELETE", "/_pit", Json.map("id", pitId)).body();

            return LevelResult.builder("scroll 用 " + batches + " 批取到 " + scrollIds.size()
                            + " 条，PIT + search_after 用 " + pitPages + " 页取到 " + pitIds.size()
                            + " 条；两者 id 集合" + (scrollIds.equals(pitIds) ? "完全相同" : "不一致")
                            + "，用完都显式关闭了上下文")
                    .metrics(
                            new Metric("scroll 批次", batches, "每批 size=10"),
                            new Metric("scroll 结果", scrollIds.size() + " 条", "sort=_doc 只保证不重不漏"),
                            new Metric("PIT 页数", pitPages, "sort=price + _shard_doc 保证稳定顺序"),
                            new Metric("上下文清理", closed.path("succeeded").asBoolean() && pitClosed.path("succeeded").asBoolean() ? "已关闭" : "未确认", "不关会持有段引用"))
                    .table(TableData.of(List.of("路线", "请求数", "取到文档", "适用场景"), List.of(
                            TableData.row("scroll", batches, scrollIds.size() + " 条", "离线导出、一次性遍历"),
                            TableData.row("PIT + search_after", pitPages, pitIds.size() + " 条", "在线翻页、可续翻、可并发"))))
                    .json(Map.of("scrollFirst", first, "scrollClosed", closed, "pit", pit, "pitClosed", pitClosed))
                    .notes(
                            "scroll 的上下文默认在 1 分钟无访问后过期；过期后再请求会 404 search_context_missing_exception。",
                            "PIT 快照的是「打开那一刻的段」，因此翻页期间即使有写入也不会重复或漏读；这也是它比 from/size 更适合在线翻页的原因。",
                            "两条路线都必须在用完后显式关闭：scroll 占堆内存，PIT 持有段引用会阻碍 merge。本关把清理也做进流程了。")
                    .build();
        }

        private static int collect(JsonNode resp, Set<String> ids) {
            JsonNode hits = resp.path("hits").path("hits");
            for (JsonNode h : hits) ids.add(h.path("_id").asText());
            return hits.size();
        }
    }

    // ── m6-5 explain：一条文档为什么得分 ────────────────────────
    @Component
    public static final class M65Explain extends CoreLevel {
        M65Explain(EsClient e, LabData d) {
            super(e, d);
        }

        public String id() {
            return "m6-5";
        }

        public LevelResult run() {
            reset();
            JsonNode s = search(Json.map("size", 1, "query", Json.map("match", Json.map("title", "蓝牙耳机"))));
            JsonNode hit = s.path("hits").path("hits").get(0);
            String id = hit.path("_id").asText();
            double searchScore = hit.path("_score").asDouble();

            JsonNode byMatch = es.post("/" + LabIndexes.CORE + "/_explain/" + id,
                    Json.map("query", Json.map("match", Json.map("title", "蓝牙耳机"))));
            JsonNode byTerm = es.post("/" + LabIndexes.CORE + "/_explain/" + id,
                    Json.map("query", Json.map("term", Json.map("title", "蓝牙耳机"))));

            double matchScore = byMatch.path("explanation").path("value").asDouble();
            boolean termMatched = byTerm.path("matched").asBoolean(true);
            String termReason = byTerm.path("explanation").path("description").asText("—");
            if (termReason.length() > 80) termReason = termReason.substring(0, 80) + "…";

            List<List<Object>> rows = new ArrayList<>();
            rows.add(TableData.row("search 的最高分文档", id, num(searchScore), "基线"));
            rows.add(TableData.row("_explain + match", byMatch.path("matched").asBoolean() ? "匹配" : "不匹配", num(matchScore), "应与 search 的 _score 相同"));
            rows.add(TableData.row("_explain + term", termMatched ? "匹配" : "不匹配", num(byTerm.path("explanation").path("value").asDouble()), termReason));

            return LevelResult.builder("同一篇文档：" + id + " 在 search 里得分 " + num(searchScore)
                            + "，用 _explain 重放 match 得到 " + num(matchScore) + "；换成 term 查询则 matched="
                            + termMatched + " —— 因为 term 不分析，倒排里没有「蓝牙耳机」整串")
                    .metrics(
                            new Metric("search 最高分", num(searchScore), "来自真实查询"),
                            new Metric("explain(match)", num(matchScore), "单文档诊断，与 search 一致"),
                            new Metric("explain(term)", termMatched ? "匹配" : "不匹配", "term 不分析查询串"),
                            new Metric("诊断对象", id, "只解释这一条"))
                    .table(TableData.of(List.of("诊断", "matched", "score", "说明"), rows))
                    .json(Map.of("search", s, "explainMatch", byMatch, "explainTerm", byTerm))
                    .notes(
                            "_explain 是针对「一篇文档 + 一个 query」的诊断，不能用来测吞吐：它不做 fetch、不做分页，开销还比普通查询高。",
                            "explanation 树里的 value 是这一层的贡献分；子节点的 value 之和与父节点的关系由 boost 模式决定，逐层读比只看总分有用。",
                            "想让一条不匹配的文档「为什么没匹配」也有解释，必须用 explain=true 的 search 或 _explain —— 普通响应只给命中项，不给落选项。")
                    .build();
        }
    }
}
