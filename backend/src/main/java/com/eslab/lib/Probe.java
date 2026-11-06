package com.eslab.lib;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 探针摘要：把一个完整的 ES 响应压缩成「这一眼只需要看的那几个数」。
 *
 * 为什么不直接把原始 JSON 丢给页面：原始响应动辄几千行，
 * 学习者很难在 _shards/_seq_no/版本字段里找到「命中数从 1 万变成 0」这个关键信号。
 * facts 是给前端直接渲染的字符串：后端决定「该看什么」，前端不需要认识每种响应。
 */
public final class Probe {
    private Probe() {
    }

    private static final String[] TOP_FIELDS = {"sku", "title", "price", "category", "brand", "status", "tags"};

    public static Map<String, Object> summarize(JsonNode d) {
        List<String> facts = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("facts", facts);

        if (d == null || d.isNull() || d.isMissingNode()) return out;

        if (d.isArray()) {
            facts.add(d.size() + " 行");
            // _cat/aliases 的行同时有 alias 与 index，必须先判 alias，
            // 否则会被当成索引行显示成「eslab-goods-xxx · - 条」
            if (!d.isEmpty() && d.get(0).has("alias")) {
                for (int i = 0; i < Math.min(4, d.size()); i++) {
                    JsonNode r = d.get(i);
                    facts.add(r.path("alias").asText() + " → " + r.path("index").asText()
                            + ("true".equals(r.path("is_write_index").asText()) ? " · 写索引" : ""));
                }
                return out;
            }
            // _cat 系：把关键列直接摊在 facts 里，省得展开原始响应找
            if (!d.isEmpty() && d.get(0).has("index")) {
                for (int i = 0; i < Math.min(4, d.size()); i++) {
                    JsonNode r = d.get(i);
                    facts.add(r.path("index").asText() + " · " + r.path("docs.count").asText("-")
                            + " 条 · " + r.path("store.size").asText("-"));
                }
            }
            return out;
        }
        if (!d.isObject()) {
            facts.add(truncate(d.asText(), 80));
            return out;
        }

        // ── 错误：探针故意触发报错时，错误信息就是结论 ──
        if (d.has("error")) {
            JsonNode err = d.get("error");
            String type;
            String reason = "";
            if (err.isTextual()) {
                type = err.asText();
            } else {
                type = err.path("type").asText("error");
                if (err.path("root_cause").isArray() && !err.path("root_cause").isEmpty()
                        && err.path("root_cause").get(0).hasNonNull("reason")) {
                    // root_cause 比顶层的 "all shards failed" 具体得多，优先用它
                    reason = err.path("root_cause").get(0).path("reason").asText("");
                }
                if (reason.isBlank()) reason = err.path("reason").asText("");
            }
            facts.add("报错 " + type);
            if (!reason.isBlank()) facts.add(truncate(reason, 110));
            out.put("errorType", type);
        }

        // ── 派生索引：_reindex / _update_by_query / _delete_by_query ──
        if (d.has("created") && d.has("batches")) {
            facts.add("重建完成：" + fmt(d.path("created").asLong()) + " 条新建"
                    + (d.path("updated").asLong() > 0 ? " / " + fmt(d.path("updated").asLong()) + " 更新" : "")
                    + (d.path("deleted").asLong() > 0 ? " / " + fmt(d.path("deleted").asLong()) + " 删除" : ""));
            facts.add(fmt(d.path("batches").asLong()) + " 批 · 冲突 " + fmt(d.path("version_conflicts").asLong())
                    + " · ES took " + d.path("took").asLong() + "ms");
            if (d.path("failures").isArray() && !d.path("failures").isEmpty()) {
                facts.add("失败 " + d.path("failures").size() + " 条（详见原始响应）");
            }
            out.put("reindexCreated", d.path("created").asLong());
            out.put("took", d.path("took").asInt());
            return out;
        }
        if (d.has("updated") && d.has("batches")) {
            facts.add("扫描 " + fmt(d.path("total").asLong()) + " 条 · 改写 " + fmt(d.path("updated").asLong())
                    + " 条 · 删除 " + fmt(d.path("deleted").asLong()) + " 条");
            facts.add(fmt(d.path("batches").asLong()) + " 批 · 冲突 " + fmt(d.path("version_conflicts").asLong())
                    + " · ES took " + d.path("took").asLong() + "ms");
            out.put("took", d.path("took").asInt());
            return out;
        }
        if (d.has("deleted") && d.has("batches")) {
            facts.add("扫描 " + fmt(d.path("total").asLong()) + " 条 · 删除 " + fmt(d.path("deleted").asLong()) + " 条");
            facts.add(fmt(d.path("batches").asLong()) + " 批 · 冲突 " + fmt(d.path("version_conflicts").asLong())
                    + " · ES took " + d.path("took").asLong() + "ms");
            out.put("took", d.path("took").asInt());
            return out;
        }

        // ── 异步任务：GET /_tasks/{id} ──
        if (d.has("completed") && d.path("task").isObject()) {
            JsonNode st = d.path("task").path("status");
            boolean done = d.path("completed").asBoolean(false);
            facts.add((done ? "任务已完成" : "任务进行中")
                    + " · 已写 " + fmt(st.path("created").asLong()) + " / " + fmt(st.path("total").asLong()) + " 条"
                    + " · " + fmt(st.path("batches").asLong()) + " 批");
            facts.add("冲突 " + fmt(st.path("version_conflicts").asLong())
                    + (st.has("requests_per_second") ? " · 限速 " + st.path("requests_per_second").asDouble() + "/s" : "")
                    + " · 运行 " + fmt(d.path("task").path("running_time_in_millis").asLong()) + "ms");
            out.put("taskCompleted", done);
            out.put("taskCreated", st.path("created").asLong());
            return out;
        }

        // ── 别名原子变更：_aliases 只回 {acknowledged, errors}，没有 action_results ──
        if (d.has("errors") && d.has("acknowledged")) {
            facts.add("别名变更已确认 · errors=" + d.path("errors").asBoolean(false));
            facts.add("errors=false 表示所有 add/remove 都成功；任何一个失败会在这里返回 true");
            out.put("aliasChanged", true);
            return out;
        }
        if (d.has("action_results")) {
            facts.add("别名变更 " + d.path("action_results").size() + " 个动作 · errors=" + d.path("errors").asBoolean(false));
            List<String> results = new ArrayList<>();
            for (JsonNode r : d.path("action_results")) results.add(r.asText());
            if (!results.isEmpty()) facts.add("每个动作结果：" + String.join(" / ", results));
            out.put("aliasActions", d.path("action_results").size());
            return out;
        }

        // ── rollover ──
        if (d.has("rolled_over")) {
            boolean rolled = d.path("rolled_over").asBoolean(false);
            facts.add(rolled ? "已滚动：旧 " + d.path("old_index").asText() + " → 新 " + d.path("new_index").asText()
                    : "未满足条件，没有滚动（当前 " + d.path("old_index").asText() + "）");
            facts.add("dry_run=" + d.path("dry_run").asBoolean(false)
                    + " · 分片确认=" + d.path("shards_acknowledged").asBoolean(false));
            out.put("rolledOver", rolled);
            return out;
        }

        // ── 索引模板 ──
        if (d.path("index_templates").isArray() && !d.path("index_templates").isEmpty()) {
            for (JsonNode t : d.path("index_templates")) {
                JsonNode def = t.path("index_template");
                List<String> patterns = new ArrayList<>();
                for (JsonNode p : def.path("index_patterns")) patterns.add(p.asText());
                facts.add("模板 " + t.path("name").asText() + " · 匹配 " + String.join(",", patterns)
                        + " · priority " + def.path("priority").asInt());
            }
            return out;
        }
        if (d.path("template").isObject()) {
            JsonNode t = d.path("template");
            JsonNode idx = t.path("settings").path("index");
            List<String> parts = new ArrayList<>();
            if (idx.hasNonNull("number_of_shards")) parts.add(idx.get("number_of_shards").asText() + " 分片");
            if (idx.hasNonNull("number_of_replicas")) parts.add(idx.get("number_of_replicas").asText() + " 副本");
            if (idx.hasNonNull("refresh_interval")) parts.add("refresh " + idx.get("refresh_interval").asText());
            facts.add("模拟结果（未创建）：" + (parts.isEmpty() ? "无 settings" : String.join(" · ", parts)));
            int fieldCount = t.path("mappings").path("properties").size();
            if (fieldCount > 0) facts.add(fieldCount + " 个字段：" +
                    String.join(", ", fieldNames(t.path("mappings").path("properties"))));
            out.put("simulatedFields", fieldCount);
            return out;
        }

        // ── ingest pipeline 模拟 ──
        if (d.path("docs").isArray() && !d.path("docs").isEmpty()
                && d.path("docs").get(0).path("doc").isObject()) {
            JsonNode doc = d.path("docs").get(0).path("doc");
            JsonNode src = doc.path("_source");
            List<String> keys = new ArrayList<>();
            Iterator<String> it = src.fieldNames();
            while (it.hasNext()) keys.add(it.next());
            facts.add("模拟 " + d.path("docs").size() + " 条 · 处理后 " + keys.size() + " 个字段");
            facts.add("字段：" + String.join(", ", keys));
            out.put("simulatedDoc", src);
            return out;
        }

        // ── bulk 混合动作 ──
        if (d.path("items").isArray()) {
            Map<String, Integer> byAction = new LinkedHashMap<>();
            Map<Integer, Integer> byStatus = new LinkedHashMap<>();
            for (JsonNode item : d.path("items")) {
                Iterator<Map.Entry<String, JsonNode>> f = item.fields();
                if (!f.hasNext()) continue;
                Map.Entry<String, JsonNode> e = f.next();
                byAction.merge(e.getKey(), 1, Integer::sum);
                byStatus.merge(e.getValue().path("status").asInt(0), 1, Integer::sum);
            }
            List<String> parts = new ArrayList<>();
            byAction.forEach((k, v) -> parts.add(k + " " + v));
            facts.add("动作 " + d.path("items").size() + " 条：" + String.join(" · ", parts));
            List<String> statuses = new ArrayList<>();
            byStatus.forEach((k, v) -> statuses.add(k + "×" + v));
            facts.add("状态码分布：" + String.join(" · ", statuses) + " · errors=" + d.path("errors").asBoolean(false));
            out.put("bulkErrors", d.path("errors").asBoolean(false));
            return out;
        }

        // ── suggest ──
        if (d.path("suggest").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = d.path("suggest").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                if (!e.getValue().isArray() || e.getValue().isEmpty()) continue;
                JsonNode opts = e.getValue().get(0).path("options");
                List<String> texts = new ArrayList<>();
                for (int i = 0; i < Math.min(4, opts.size()); i++) {
                    JsonNode o = opts.get(i);
                    texts.add(o.path("text").asText()
                            + (o.hasNonNull("_score") ? "(" + String.format("%.2f", o.path("_score").asDouble()) + ")" : ""));
                }
                facts.add(e.getKey() + "：" + (texts.isEmpty() ? "无候选" : String.join(" / ", texts)));
            }
            return out;
        }

        // ── forcemerge：只有 _shards，没有 hits ──
        if (d.has("_shards") && !d.has("hits") && !d.has("created")) {
            JsonNode s = d.path("_shards");
            facts.add("分片 " + s.path("successful").asInt() + "/" + s.path("total").asInt()
                    + " 成功 · 失败 " + s.path("failed").asInt());
            return out;
        }

        // ── 索引就绪 / 一般确认 ──
        if (d.has("acknowledged") && d.hasNonNull("index")) {
            facts.add("索引 " + d.path("index").asText() + " 就绪 · 分片确认 "
                    + d.path("shards_acknowledged").asBoolean(true));
            return out;
        }
        if (d.has("acknowledged") && !d.has("action_results")) {
            facts.add("已确认 acknowledged=" + d.path("acknowledged").asBoolean(false));
            return out;
        }

        // ── search ──
        JsonNode hits = d.path("hits");
        if (hits.isObject()) {
            JsonNode total = hits.path("total");
            if (total.isNumber()) {
                facts.add("命中 " + fmt(total.asLong()));
                out.put("hitsTotal", total.asLong());
            } else if (total.isObject()) {
                long v = total.path("value").asLong();
                String relation = total.path("relation").asText("eq");
                facts.add(("gte".equals(relation) ? "命中 ≥" : "命中 ") + fmt(v));
                out.put("hitsTotal", v);
                out.put("hitsRelation", relation);
            }
            if (hits.path("max_score").isNumber()) {
                facts.add("最高分 " + String.format("%.2f", hits.path("max_score").asDouble()));
                out.put("maxScore", hits.path("max_score").asDouble());
            }
            JsonNode arr = hits.path("hits");
            if (arr.isArray() && !arr.isEmpty()) {
                List<Map<String, Object>> top = new ArrayList<>();
                for (int i = 0; i < Math.min(3, arr.size()); i++) {
                    JsonNode h = arr.get(i);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("_id", h.path("_id").asText());
                    if (h.path("_score").isNumber()) {
                        row.put("_score", Timing.round(h.path("_score").asDouble(), 3));
                    }
                    JsonNode src = h.path("_source");
                    for (String f : TOP_FIELDS) {
                        if (src.has(f)) row.put(f, src.get(f));
                    }
                    top.add(row);
                }
                out.put("top", top);
            }
        }
        if (d.path("count").isNumber()) {
            facts.add("count " + fmt(d.path("count").asLong()));
            out.put("hitsTotal", d.path("count").asLong());
        }
        if (d.path("took").isNumber()) {
            facts.add("ES took " + d.path("took").asInt() + "ms");
            out.put("took", d.path("took").asInt());
        }

        // ── _analyze ──
        if (d.path("tokens").isArray()) {
            List<String> tokens = new ArrayList<>();
            for (JsonNode t : d.path("tokens")) tokens.add(t.path("token").asText());
            String shown = String.join(" / ", tokens.subList(0, Math.min(8, tokens.size())));
            facts.add("切出 " + tokens.size() + " 个词：" + shown + (tokens.size() > 8 ? " …" : ""));
            out.put("tokens", tokens);
        }

        // ── 聚合 ──
        JsonNode aggs = d.path("aggregations");
        if (aggs.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = aggs.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String agg = e.getKey();
                JsonNode val = e.getValue();
                if (val.path("buckets").isArray()) {
                    JsonNode buckets = val.path("buckets");
                    String first = "";
                    if (!buckets.isEmpty()) {
                        JsonNode b = buckets.get(0);
                        first = "，首位 " + b.path("key").asText() + " " + fmt(b.path("doc_count").asLong());
                    }
                    facts.add(agg + ": " + buckets.size() + " 桶" + first);
                    if (val.has("doc_count_error_upper_bound")) {
                        facts.add("误差上界 ±" + val.path("doc_count_error_upper_bound").asLong());
                    }
                    long other = val.path("sum_other_doc_count").asLong();
                    if (other > 0) facts.add("未进 Top：" + fmt(other) + " 条");
                } else if (val.has("values") && val.path("values").isArray()) {
                    List<String> pairs = new ArrayList<>();
                    for (JsonNode v : val.path("values")) {
                        if (v.has("key")) {
                            pairs.add("P" + v.path("key").asText() + "="
                                    + String.format("%.1f", v.path("value").asDouble()));
                        }
                    }
                    if (!pairs.isEmpty()) facts.add(agg + ": " + String.join(" ", pairs));
                } else if (val.has("value")) {
                    facts.add(agg + ": " + val.path("value").asText());
                }
            }
        }

        // ── mapping / field_caps ──
        List<Map<String, Object>> fields = new ArrayList<>();
        JsonNode props = d.path("mappings").path("properties");
        if (props.isObject()) {
            flattenProperties(props, "", fields);
        } else {
            // GET /{index}/_mapping 的响应外面套了一层索引名：
            // {"eslab-products-v1": {"mappings": {"properties": {...}}}}
            Iterator<Map.Entry<String, JsonNode>> idxIt = d.fields();
            while (idxIt.hasNext()) {
                JsonNode idxProps = idxIt.next().getValue().path("mappings").path("properties");
                if (idxProps.isObject()) flattenProperties(idxProps, "", fields);
            }
        }
        if (fields.isEmpty() && d.path("fields").isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = d.path("fields").fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String type = "?";
                Iterator<JsonNode> variants = e.getValue().elements();
                if (variants.hasNext()) type = variants.next().path("type").asText("?");
                fields.add(Map.of("field", e.getKey(), "type", type));
            }
        }
        if (!fields.isEmpty()) {
            facts.add(fields.size() + " 个字段");
            List<String> interesting = new ArrayList<>();
            for (Map<String, Object> f : fields) {
                String name = String.valueOf(f.get("field"));
                if (List.of("sku", "title", "price", "status", "tags", "title.keyword").contains(name)) {
                    interesting.add(name + "=" + f.get("type"));
                }
            }
            if (!interesting.isEmpty()) facts.add(String.join(" · ", interesting));
            out.put("fieldTypes", fields);
        }

        // ── _stats ──
        JsonNode allPrimaries = d.path("_all").path("primaries");
        JsonNode indexing = allPrimaries.path("indexing");
        if (indexing.isObject() && indexing.has("index_total")) {
            long total = indexing.path("index_total").asLong();
            long timeMs = indexing.path("index_time_in_millis").asLong();
            if (total > 0) {
                facts.add("累计写入 " + fmt(total) + " 条 / " + fmt(timeMs) + "ms");
                facts.add(String.format("%.3f ms/条", (double) timeMs / total));
            }
        }
        if (allPrimaries.path("refresh").isObject() || allPrimaries.path("flush").isObject()) {
            long r = allPrimaries.path("refresh").path("total").asLong();
            long f = allPrimaries.path("flush").path("total").asLong();
            if (r > 0 || f > 0) facts.add("refresh " + fmt(r) + " 次 · flush " + fmt(f) + " 次");
        }
        JsonNode segCount = d.path("_all").path("total").path("segments").path("count");
        if (segCount.isNumber()) facts.add("段 " + fmt(segCount.asLong()) + " 个");
        JsonNode search = allPrimaries.path("search");
        if (search.isObject() && search.has("query_total")) {
            long q = search.path("query_total").asLong();
            if (q > 0) {
                facts.add("累计查询 " + fmt(q) + " 次 / "
                        + fmt(search.path("query_time_in_millis").asLong()) + "ms");
            }
        }
        JsonNode cache = allPrimaries.path("request_cache");
        if (cache.isObject() && cache.has("hit_count")) {
            facts.add("request cache 命中 " + fmt(cache.path("hit_count").asLong())
                    + " / 未命中 " + fmt(cache.path("miss_count").asLong()));
        }

        // ── 集群健康（GET /_cluster/health）──
        if (d.hasNonNull("status") && d.has("number_of_nodes")) {
            String status = d.path("status").asText();
            facts.add("状态 " + status + " · " + d.path("number_of_nodes").asInt() + " 节点");
            if (d.has("active_primary_shards")) {
                facts.add("主分片 " + d.path("active_primary_shards").asInt()
                        + " · 活跃 " + d.path("active_shards").asInt());
            }
            int unassigned = d.path("unassigned_shards").asInt(0);
            if (unassigned > 0) {
                facts.add("未分配分片 " + unassigned + "（单节点下副本没有第二个节点可放）");
            }
            out.put("clusterStatus", status);
        }

        // ── settings ──
        Iterator<JsonNode> indexEntries = d.elements();
        if (indexEntries.hasNext()) {
            JsonNode settings = indexEntries.next().path("settings").path("index");
            if (settings.isObject()) {
                List<String> parts = new ArrayList<>();
                if (settings.hasNonNull("number_of_shards")) parts.add(settings.get("number_of_shards").asText() + " 分片");
                if (settings.hasNonNull("number_of_replicas")) parts.add(settings.get("number_of_replicas").asText() + " 副本");
                if (settings.hasNonNull("refresh_interval")) parts.add("refresh " + settings.get("refresh_interval").asText());
                String durability = settings.path("translog").path("durability").asText("");
                if (!durability.isBlank()) parts.add("translog " + durability);
                if (!parts.isEmpty()) facts.add(String.join(" · ", parts));
            }
        }

        // ── profile ──
        JsonNode profileQuery = d.path("profile").path("shards").path(0)
                .path("searches").path(0).path("query");
        if (profileQuery.isArray()) {
            List<double[]> rows = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (JsonNode q : profileQuery) walkProfile(q, rows, types);
            Integer[] order = new Integer[rows.size()];
            for (int i = 0; i < order.length; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Double.compare(rows.get(b)[0], rows.get(a)[0]));
            java.util.Set<String> seen = new java.util.HashSet<>();
            int shown = 0;
            for (int idx : order) {
                if (!seen.add(types.get(idx))) continue;
                facts.add(types.get(idx) + " " + String.format("%.2f", rows.get(idx)[0] / 1_000_000.0) + "ms");
                if (++shown >= 4) break;
            }
        }

        // ── 节点线程池 / 堆 ──
        JsonNode nodes = d.path("nodes");
        if (nodes.isObject() && nodes.size() > 0) {
            JsonNode first = nodes.elements().next();
            JsonNode write = first.path("thread_pool").path("write");
            if (write.isObject()) {
                facts.add("write 线程池 队列 " + write.path("queue").asInt()
                        + " · 拒绝 " + write.path("rejected").asInt());
            }
            if (first.path("jvm").path("mem").has("heap_used_percent")) {
                facts.add("堆占用 " + first.path("jvm").path("mem").path("heap_used_percent").asInt() + "%");
            }
            // GET /_nodes/stats/indices/search 走的是 nodes.*.indices.search，不是顶层的 _all
            JsonNode nodeSearch = first.path("indices").path("search");
            if (nodeSearch.isObject() && nodeSearch.has("query_total")) {
                long q = nodeSearch.path("query_total").asLong();
                long qt = nodeSearch.path("query_time_in_millis").asLong();
                facts.add("节点累计查询 " + fmt(q) + " 次 / " + fmt(qt) + "ms");
                if (q > 0 && qt > 0) {
                    facts.add(String.format("平均 %.2f ms/次（累计均值，不是单次耗时）", (double) qt / q));
                }
                if (nodeSearch.has("fetch_total")) {
                    facts.add("fetch " + fmt(nodeSearch.path("fetch_total").asLong()) + " 次 / "
                            + fmt(nodeSearch.path("fetch_time_in_millis").asLong()) + "ms");
                }
            }
        }

        // ── PIT ──
        String id = d.path("id").asText("");
        if (id.length() > 40) facts.add("PIT 已创建（id 每次不同，默认 1 分钟后自动释放）");

        // ── 按 _id 取文档 ──
        if (d.has("found")) {
            boolean found = d.get("found").asBoolean();
            facts.add(found ? "found=true：这个 _id 存在" : "found=false：这个 _id 不存在");
            out.put("found", found);
        }

        // ── 写操作应答 ──
        if (d.hasNonNull("result")) {
            String suffix = d.hasNonNull("_id") ? " · _id=" + truncate(d.get("_id").asText(), 24) : "";
            facts.add(d.get("result").asText() + suffix);
        }

        return out;
    }

    private static List<String> fieldNames(JsonNode props) {
        List<String> names = new ArrayList<>();
        Iterator<String> it = props.fieldNames();
        while (it.hasNext()) names.add(it.next());
        return names;
    }

    private static void flattenProperties(JsonNode props, String prefix, List<Map<String, Object>> out) {        Iterator<Map.Entry<String, JsonNode>> it = props.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String full = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            JsonNode def = e.getValue();
            if (def.has("properties")) {
                flattenProperties(def.get("properties"), full, out);
            } else {
                out.add(Map.of("field", full, "type", def.path("type").asText("object")));
            }
        }
    }

    private static void walkProfile(JsonNode node, List<double[]> rows, List<String> types) {
        if (node == null || node.isMissingNode()) return;
        if (node.hasNonNull("type")) {
            rows.add(new double[]{node.path("time_in_nanos").asDouble()});
            types.add(node.get("type").asText());
        }
        for (JsonNode child : node.path("children")) walkProfile(child, rows, types);
    }

    private static String fmt(long n) {
        return String.format("%,d", n);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
