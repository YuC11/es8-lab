package com.eslab.web;

import com.eslab.data.DatasetService;
import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabGate;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 实验环境的复位与重建。
 *
 * 为什么不能只靠 sh/clean.sh：清理是「命令行、全量、离线」的，而学员需要的是
 * 「只清这一章」「先看看会删什么」「清完还能重建」—— 这些必须知道索引的生命周期，
 * 而生命周期只在 LabIndexes 里。所以这里是注册表的第二个消费者（第一个是 LabData）。
 *
 * 安全边界：只接受注册表里的名字，不做通配、不做前缀删除。宁可让用户多点几次，
 * 也不要出现一条 DELETE /eslab-* 把别人的索引带走。
 */
@Service
public class LabResetService {

    private static final Pattern SAFE =
            Pattern.compile("^eslab-(goods|demo|products)-[a-zA-Z0-9][a-zA-Z0-9._-]*$");

    private final EsClient es;
    private final LabIndexes indexes;
    private final LabData lab;
    private final DatasetService dataset;
    private final LabGate gate;

    public LabResetService(EsClient es, LabIndexes indexes, LabData lab,
                           DatasetService dataset, LabGate gate) {
        this.es = es;
        this.indexes = indexes;
        this.lab = lab;
        this.dataset = dataset;
        this.gate = gate;
    }

    // ── 总览 ────────────────────────────────────────────────

    /** 注册表 + 运行时状态 + 别名 + 集群资产，前端索引面板与回归脚本共用 */
    public Map<String, Object> overview() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LabIndexes.IndexSpec spec : indexes.all()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", spec.name());
            row.put("chapter", spec.chapter());
            row.put("owner", spec.ownerLevel());
            row.put("lifecycle", spec.lifecycle().name());
            row.put("resetCost", spec.resetCost().name());
            row.put("targetDocs", spec.targetDocs());
            row.put("aliases", spec.aliases());
            row.put("writable", spec.labWritable());
            row.put("note", spec.note());
            Map<String, Object> stat = statOf(spec.name());
            row.put("exists", stat.get("exists"));
            row.put("docs", stat.get("docs"));
            row.put("shards", stat.get("shards"));
            row.put("replicas", stat.get("replicas"));
            row.put("segments", stat.get("segments"));
            row.put("storeBytes", stat.get("storeBytes"));
            rows.add(row);
        }

        List<Map<String, Object>> aliasRows = new ArrayList<>();
        for (LabIndexes.AliasSpec a : indexes.aliases()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", a.name());
            row.put("defaultTarget", a.defaultTarget());
            row.put("candidates", a.candidates());
            String target = lab.writeTargetOf(a.name());
            row.put("writeIndex", target == null ? "" : target);
            row.put("docs", target == null ? -1 : lab.countQuietly(a.name()));
            aliasRows.add(row);
        }

        List<Map<String, Object>> assetRows = new ArrayList<>();
        for (LabIndexes.AssetSpec a : indexes.assets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", a.type());
            row.put("name", a.name());
            row.put("pattern", a.pattern() == null ? "" : a.pattern());
            row.put("chapter", a.chapter());
            row.put("exists", assetExists(a));
            assetRows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indices", rows);
        out.put("aliases", aliasRows);
        out.put("assets", assetRows);
        out.put("resources", resourceRows());
        out.put("writing", gate.writing());
        return out;
    }

    /**
     * 每关声明自己占用什么、谁负责收尾 —— 从注册表推导，不让关卡再手写一遍。
     * 审计脚本按这份声明核对「跑完之后有没有留下不该留的东西」。
     */
    private Map<String, Object> resourceRows() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, LabIndexes.Resources> e : indexes.resourcesByLevel().entrySet()) {
            LabIndexes.Resources r = e.getValue();
            if (r.empty()) continue;
            Map<String, Object> one = new LinkedHashMap<>();
            List<Map<String, Object>> idx = new ArrayList<>();
            for (LabIndexes.IndexSpec s : r.indices()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", s.name());
                m.put("lifecycle", s.lifecycle().name());
                m.put("targetDocs", s.targetDocs());
                m.put("cleanup", cleanupOf(s.lifecycle()));
                idx.add(m);
            }
            one.put("indices", idx);
            one.put("aliases", r.aliases());
            List<Map<String, Object>> as = new ArrayList<>();
            for (LabIndexes.AssetSpec a : r.assets()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", a.type());
                m.put("name", a.name());
                m.put("cleanup", "由关卡自己管理（模板类在 finally 删、pipeline 保留）；clean.sh 与复位面板可按登记名清");
                as.add(m);
            }
            one.put("assets", as);
            List<Map<String, Object>> cs = new ArrayList<>();
            for (LabIndexes.ContextSpec c : r.contexts()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("kind", c.kind());
                m.put("note", c.note());
                m.put("cleanup", "关卡显式关闭；审计要求 open_contexts = 0");
                cs.add(m);
            }
            one.put("contexts", cs);
            out.put(e.getKey(), one);
        }
        return out;
    }

    private static String cleanupOf(LabIndexes.Lifecycle lifecycle) {
        return switch (lifecycle) {
            case PERSISTENT -> "常驻；只在复位面板或 clean.sh 里清";
            case RESET_ON_RUN, RECREATE_ON_RUN -> "每次运行前先删再建，重跑不累积";
            case DROP_AFTER_RUN -> "关卡结束删除，异常路径也删";
            case ROLLOVER -> "常驻，随 rollover 新增 -00000N";
            case PLANNED -> "只模拟，永不创建";
        };
    }

    // ── 复位 ────────────────────────────────────────────────

    public Map<String, Object> reset(Map<String, Object> req) throws Exception {
        boolean dryRun = bool(req.get("dryRun"), true);
        String scope = str(req.get("scope"), "all");
        String chapter = str(req.get("chapter"), "m8");
        String family = str(req.get("family"), LabIndexes.PREFIX);
        String index = str(req.get("index"), "");
        boolean includeProducts = bool(req.get("includeProducts"), false);

        List<LabIndexes.IndexSpec> targets = resolve(scope, chapter, family, index, includeProducts);
        List<LabIndexes.AssetSpec> assets = resolveAssets(scope, chapter, family);

        return gate.write(() -> {
            List<String> deletedIndices = new ArrayList<>();
            List<String> deletedAssets = new ArrayList<>();
            List<String> skipped = new ArrayList<>();

            Map<String, Object> deleted = new LinkedHashMap<>();
            List<String> aliasNames = touchedAliases(targets);

            if (!dryRun) {
                // 先删资产再删索引：否则删完索引后模板还在，下一次建索引又会套上
                for (LabIndexes.AssetSpec a : assets) {
                    if (!assetExists(a)) continue;
                    String path = ("template".equals(a.type()) ? "/_index_template/" : "/_ingest/pipeline/")
                            + a.name();
                    es.delete(path);
                    deletedAssets.add(a.type() + ":" + a.name());
                }
                for (LabIndexes.IndexSpec spec : targets) {
                    if (!lab.exists(spec.name())) {
                        skipped.add(spec.name() + "（不存在）");
                        continue;
                    }
                    // 产品索引归 DatasetService 管，其它归 LabData —— 两边都只删自己那部分
                    if (spec.name().startsWith("eslab-products-")) {
                        dataset.deleteIfExists(spec.name());
                    } else {
                        lab.delete(spec.name());
                    }
                    deletedIndices.add(spec.name());
                }
            } else {
                for (LabIndexes.IndexSpec spec : targets) {
                    if (!lab.exists(spec.name())) skipped.add(spec.name() + "（不存在）");
                    else deletedIndices.add(spec.name());
                }
                for (LabIndexes.AssetSpec a : assets) {
                    if (assetExists(a)) deletedAssets.add(a.type() + ":" + a.name());
                }
            }

            deleted.put("indices", deletedIndices);
            deleted.put("aliases", aliasNames);
            deleted.put("assets", deletedAssets);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dryRun", dryRun);
            out.put("scope", scope);
            out.put("target", index);
            out.put("deleted", deleted);
            out.put("skipped", skipped);
            out.put("remaining", remaining());
            out.put("writing", false);
            return out;
        });
    }

    // ── 重建 ────────────────────────────────────────────────

    public Map<String, Object> rebuild(Map<String, Object> req) throws Exception {
        boolean dryRun = bool(req.get("dryRun"), true);
        String scope = str(req.get("scope"), "baseline");
        String chapter = str(req.get("chapter"), "m8");
        String index = str(req.get("index"), "");
        int count = intOf(req.get("count"), DatasetService.DEFAULT_TARGET);

        // 先把「要重建什么」算出来，dryRun 与真实执行共用同一份计划 ——
        // 否则预览列表和实际动作会各写一遍，迟早漂移。
        List<String> plan = new ArrayList<>();
        switch (scope) {
            case "baseline" -> plan.addAll(List.of(DatasetService.V1, DatasetService.V2));
            case "chapter" -> {
                for (LabIndexes.IndexSpec spec : indexes.byChapter(chapter)) {
                    if (spec.labWritable()) plan.add(spec.name());
                }
            }
            case "index" -> {
                if (index.isBlank()) throw new IllegalArgumentException("scope=index 时必须给 index");
                LabIndexes.IndexSpec spec = indexes.require(index);
                if (!spec.labWritable()) {
                    throw new IllegalArgumentException(spec.name() + " 由 DatasetService 管，请用 scope=baseline");
                }
                plan.add(spec.name());
            }
            default -> throw new IllegalArgumentException("未知 scope：" + scope
                    + "（只支持 baseline / chapter / index）");
        }

        if (dryRun) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dryRun", true);
            out.put("scope", scope);
            out.put("wouldRebuild", plan);
            out.put("docs", "baseline".equals(scope) ? (long) count * plan.size() : estimate(plan));
            out.put("indices", remaining());
            return out;
        }

        return gate.write(() -> {
            List<String> rebuilt = new ArrayList<>();
            long docs = 0;

            for (String name : plan) {
                if (name.startsWith("eslab-products-")) {
                    dataset.deleteIfExists(name);
                    dataset.create(name.equals(DatasetService.V1) ? "v1" : "v2");
                    DatasetService.BulkOutcome o = dataset.bulkLoad(name, count, 0, p -> {
                    });
                    rebuilt.add(name);
                    docs += o.docs();
                } else {
                    docs += ensure(indexes.require(name));
                    rebuilt.add(name);
                }
            }

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("dryRun", false);
            out.put("scope", scope);
            out.put("rebuilt", rebuilt);
            out.put("docs", docs);
            out.put("indices", remaining());
            return out;
        });
    }

    /** 预览时的条数估算：按登记的目标条数，仅用于让人知道量级 */
    private long estimate(List<String> names) {
        long sum = 0;
        for (String name : names) {
            sum += Math.max(0, indexes.require(name).targetDocs());
        }
        return sum;
    }

    /** 按登记的生命周期选重建方式：一次性索引不重建，reindex 类走 ensureByReindex */
    private long ensure(LabIndexes.IndexSpec spec) {
        return switch (spec.lifecycle()) {
            case PLANNED, DROP_AFTER_RUN -> 0;
            case RESET_ON_RUN, RECREATE_ON_RUN -> 0;   // 由关卡自己清空，重建没有意义
            default -> switch (spec.source()) {
                case REINDEX_V2, REINDEX_ALIAS -> lab.ensureByReindex(spec.name()).docs();
                case SELF -> lab.ensure(spec.name()).docs();
            };
        };
    }

    // ── 解析与校验 ───────────────────────────────────────────

    private List<LabIndexes.IndexSpec> resolve(String scope, String chapter, String family,
                                               String index, boolean includeProducts) {
        List<LabIndexes.IndexSpec> out = new ArrayList<>();
        switch (scope) {
            case "index" -> {
                if (index.isBlank()) throw new IllegalArgumentException("scope=index 时必须给 index");
                if (index.contains("*") || index.contains(",") || index.contains("/")) {
                    throw new IllegalArgumentException("不接受通配或路径：" + index);
                }
                LabIndexes.IndexSpec spec = indexes.require(index);
                if (!spec.labWritable() && !includeProducts) {
                    throw new IllegalArgumentException(
                            spec.name() + " 是课程主数据，确认要删请加 includeProducts=true");
                }
                out.add(spec);
            }
            case "chapter" -> {
                for (LabIndexes.IndexSpec spec : indexes.byChapter(chapter)) {
                    if (spec.labWritable() || includeProducts) out.add(spec);
                }
            }
            case "family" -> {
                if (!List.of("eslab-goods", "eslab-demo", "eslab-products").contains(family)) {
                    throw new IllegalArgumentException("family 只支持 eslab-goods / eslab-demo / eslab-products");
                }
                for (LabIndexes.IndexSpec spec : indexes.all()) {
                    if (!spec.name().startsWith(family + "-")) continue;
                    if (spec.labWritable() || includeProducts) out.add(spec);
                }
            }
            case "all" -> {
                for (LabIndexes.IndexSpec spec : indexes.all()) {
                    if (spec.labWritable() || includeProducts) out.add(spec);
                }
            }
            default -> throw new IllegalArgumentException("未知 scope：" + scope);
        }
        for (LabIndexes.IndexSpec spec : out) {
            if (!SAFE.matcher(spec.name()).matches()) {
                throw new IllegalArgumentException("拒绝删除非实验索引：" + spec.name());
            }
        }
        return out;
    }

    private List<LabIndexes.AssetSpec> resolveAssets(String scope, String chapter, String family) {
        List<LabIndexes.AssetSpec> out = new ArrayList<>();
        if ("chapter".equals(scope)) {
            for (LabIndexes.AssetSpec a : indexes.assets()) {
                if (a.chapter().equals(chapter)) out.add(a);
            }
        } else if ("all".equals(scope) || ("family".equals(scope) && family.equals(LabIndexes.PREFIX))) {
            out.addAll(indexes.assets());
        }
        return out;
    }

    private List<String> touchedAliases(List<LabIndexes.IndexSpec> targets) {
        List<String> names = new ArrayList<>();
        for (LabIndexes.AliasSpec a : indexes.aliases()) {
            for (LabIndexes.IndexSpec s : targets) {
                if (a.candidates().contains(s.name()) || a.name().equals(s.name())) {
                    names.add(a.name());
                    break;
                }
            }
        }
        return names;
    }

    private Map<String, Object> remaining() {
        List<String> indices = new ArrayList<>();
        try {
            JsonNode arr = es.get("/_cat/indices/eslab-*?format=json&h=index&s=index");
            for (JsonNode r : arr) indices.add(r.path("index").asText());
        } catch (Exception ignored) {
            // 一个都没有时 _cat 返回空数组
        }
        List<String> assets = new ArrayList<>();
        for (LabIndexes.AssetSpec a : indexes.assets()) {
            if (assetExists(a)) assets.add(a.type() + ":" + a.name());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indices", indices);
        out.put("assets", assets);
        return out;
    }

    private Map<String, Object> statOf(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exists", false);
        out.put("docs", -1);
        out.put("shards", -1);
        out.put("replicas", -1);
        out.put("segments", -1);
        out.put("storeBytes", -1);
        try {
            JsonNode row = es.get("/_cat/indices/" + name
                    + "?format=json&h=index,docs.count,pri,rep,segments.count,store.size").path(0);
            if (row.isMissingNode() || row.isEmpty()) return out;
            out.put("exists", true);
            out.put("docs", row.path("docs.count").asLong(0));
            out.put("shards", row.path("pri").asInt(0));
            out.put("replicas", row.path("rep").asInt(0));
            out.put("segments", row.path("segments.count").asLong(0));
            out.put("storeBytes", bytes(row.path("store.size").asText("0b")));
        } catch (Exception ignored) {
            // 索引不存在
        }
        return out;
    }

    private boolean assetExists(LabIndexes.AssetSpec a) {
        try {
            String path = "template".equals(a.type())
                    ? "/_index_template/" + a.name() : "/_ingest/pipeline/" + a.name();
            es.call("HEAD", path, null, null, 5_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static long bytes(String raw) {
        String s = raw == null ? "" : raw.trim().toLowerCase();
        double n = 0;
        try {
            int i = 0;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) i++;
            n = Double.parseDouble(s.substring(0, i));
            String unit = s.substring(i);
            return switch (unit) {
                case "kb" -> (long) (n * 1024);
                case "mb" -> (long) (n * 1024 * 1024);
                case "gb" -> (long) (n * 1024 * 1024 * 1024);
                case "tb" -> (long) (n * 1024L * 1024 * 1024 * 1024);
                default -> (long) n;
            };
        } catch (Exception e) {
            return -1;
        }
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    private static boolean bool(Object o, boolean fallback) {
        return o == null ? fallback : Boolean.parseBoolean(String.valueOf(o));
    }

    private static int intOf(Object o, int fallback) {
        return o instanceof Number n ? n.intValue() : fallback;
    }
}
