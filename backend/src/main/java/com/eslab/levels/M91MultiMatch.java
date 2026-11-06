package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m9-1：multi_match 的三种打分模式。
 *
 * 同一个 query、同一组字段，只换 type，命中数完全一样、分数与排序不同 ——
 * 这就是「相关性」和「过滤」的区别：过滤是 Yes/No，相关性是排序。
 *
 * 三处刻意的设计：
 *   · 只看 max_score 不够：还要看 top-3 的 _id 与逐条分数，排序变了才算真的变了；
 *   · tie_breaker 统一出现在两组变体里 —— 给 most_fields 加上它，most_fields 就退化成 best_fields；
 *   · 每组取 Top1 调一次 _explain，把「max of / sum of / max plus 0.3 times others」摊开看。
 */
@Component
public class M91MultiMatch implements LevelModule {

    private static final String SEARCH = LabIndexes.SEARCH;
    private static final String QUERY = "小米 降噪";
    private static final List<String> FIELDS = List.of("title^3", "description", "brand^2");

    private final EsClient es;
    private final LabData lab;

    public M91MultiMatch(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m9-1";
    }

    @Override
    public LevelResult run() throws Exception {
        lab.ensureSearch();

        // @focus-start 三组基线统一不设 tie_breaker，另加两组 +0.3 变体作对照
        Probe best = search("best_fields", null);
        Probe most = search("most_fields", null);
        Probe cross = search("cross_fields", null);
        Probe bestTb = search("best_fields", 0.3);
        Probe mostTb = search("most_fields", 0.3);
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(row("best_fields", "—", best));
        rows.add(row("most_fields", "—", most));
        rows.add(row("cross_fields", "—", cross));
        rows.add(row("best_fields", "0.3", bestTb));
        rows.add(row("most_fields", "0.3", mostTb));

        boolean sameTotal = best.total == most.total && most.total == cross.total
                && cross.total == bestTb.total && bestTb.total == mostTb.total;
        // 加了 tie_breaker 之后，most_fields 的 Top-3 与 best_fields 完全一致 —— 这就是「退化」的证据
        boolean mostTbEqualsBestTb = mostTb.sameTop3(bestTb);
        boolean mostDiffersFromBest = !most.sameTop3(best);

        return LevelResult.builder("同一 query、同一组字段，命中都是 " + fmt(best.total)
                        + " 条：most_fields 把各字段分数相加得 " + score(most.maxScore)
                        + "，best_fields 取最高再加 0.3×其余得 " + score(bestTb.maxScore)
                        + "；给 most_fields 也加 tie_breaker，它的 Top-3 与 best_fields "
                        + (mostTbEqualsBestTb ? "逐条相同" : "仍然不同") + " —— 参数把算法换掉了")
                .metrics(
                        new Metric("best_fields", score(bestTb.maxScore),
                                "tie_breaker 0.3 · Top1 " + bestTb.topId()),
                        new Metric("most_fields", score(most.maxScore),
                                "无 tie_breaker · Top1 " + most.topId()),
                        new Metric("cross_fields", score(cross.maxScore),
                                "把多个字段当一个整体 · Top1 " + cross.topId()),
                        new Metric("most_fields + tb", score(mostTb.maxScore),
                                mostTbEqualsBestTb ? "与 best_fields 逐条相同" : "与 best_fields 不同"),
                        new Metric("命中数是否相同", sameTotal ? "相同" : "不同",
                                fmt(best.total) + " 条，换 type 不影响命中集"))
                .table(TableData.of(
                        List.of("模式", "tie_breaker", "命中", "Top1", "Top2", "Top3", "explain 根节点"),
                        rows))
                .json(Json.map(
                        "query", QUERY,
                        "fields", FIELDS,
                        "baseline", Json.map(
                                "best_fields", best.json(),
                                "most_fields", most.json(),
                                "cross_fields", cross.json()),
                        "tieBreaker", Json.map(
                                "best_fields_0.3", bestTb.json(),
                                "most_fields_0.3", mostTb.json()),
                        "mostFieldsCollapsesToBest", mostTbEqualsBestTb,
                        "baselineMostDiffersFromBest", mostDiffersFromBest))
                .notes(
                        "对比只看 max_score 是不够的：这一关把 Top-3 的 _id 与逐条分数都摆出来，排序真的变了才叫变了",
                        "title^3 表示标题的分数按 3 倍计：词出现在标题里，通常比出现在描述里更相关",
                        "most_fields 的分数天然偏高（同一批词在每个字段各算一次再加起来），不要拿它的绝对值与别的模式比",
                        "实测坑：most_fields 加了 tie_breaker 之后，explain 根节点变成「max plus 0.3 times others」，与 best_fields 完全一致 —— 这个参数把它切换到了另一套算法",
                        "cross_fields 适合「品牌在 brand、型号在 title」这类拆开的字段：它把多个字段当成一个大字段来匹配",
                        "排序显式写成 _score desc + sku asc：同分时靠 sku 定序，否则分片返回顺序会让两次运行的 Top-3 抖动",
                        "表格里的 _id 截断成前 6 位（自动 _id 有 20 个字符，三列就撑不下），完整值在下方原始响应里",
                        "分数会随索引重建浮动 0.3% 左右：课程数据用自动 _id，重建后 _id 变了 → 文档落到不同分片 → "
                                + "每个分片各自的 avgFieldLength 随之变化 → BM25 分数微调。"
                                + "结论（哪种模式分高、加 tie_breaker 后 most_fields 是否退化）不受影响")
                .build();
    }

    private static List<Object> row(String type, String tb, Probe p) {
        return TableData.row(type, tb, fmt(p.total), p.at(0), p.at(1), p.at(2), explainLabel(p.explain));
    }

    /**
     * explain 的根节点描述太啰嗦（"max plus 0.3 times others of:"），
     * 七列表格里会把最后一列挤到视口外。只保留算法名本身，原文放在 json 里。
     */
    private static String explainLabel(String root) {
        if (root == null || root.isBlank()) return "—";
        String t = root.replaceAll("\\s+of:$", "")
                .replace("max plus 0.3 times others", "max + 0.3×others");
        return t.length() <= 22 ? t : t.substring(0, 21) + "…";
    }

    /**
     * 跑一次查询：size=3 + 显式排序 + 对 Top1 做 _explain。
     *
     * @param tieBreaker null 表示不传该参数（cross_fields 不接受它）
     */
    private Probe search(String type, Double tieBreaker) {
        Map<String, Object> mm = multiMatch(type, tieBreaker);
        JsonNode d = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 3,
                "track_total_hits", true,
                // 同分时用 sku 兜底定序，避免分片顺序让 Top-3 在两次运行间抖动
                "sort", List.of(
                        Json.map("_score", Json.map("order", "desc")),
                        Json.map("sku", Json.map("order", "asc", "missing", "_last"))),
                "query", Json.map("multi_match", mm)));

        JsonNode hits = d.path("hits");
        List<String> ids = new ArrayList<>();
        List<Double> scores = new ArrayList<>();
        for (JsonNode h : hits.path("hits")) {
            ids.add(h.path("_id").asText("—"));
            scores.add(h.path("_score").asDouble(0));
        }

        String explain = "—";
        String root = "—";
        if (!ids.isEmpty()) {
            JsonNode ex = es.post("/" + SEARCH + "/_explain/" + enc(ids.get(0)),
                    Json.map("query", Json.map("multi_match", multiMatch(type, tieBreaker))));
            if (ex.path("matched").asBoolean(false)) {
                root = ex.path("explanation").path("description").asText("—");
                explain = trim(root);
            } else {
                explain = "matched=false";
                root = "matched=false";
            }
        }

        return new Probe(hits.path("total").path("value").asLong(0),
                // 带 sort 的请求里 hits.max_score 是 null（不再按分数做全局最大值统计），
                // 而我们的排序就是 _score desc，所以 Top1 的分数就是最高分
                hits.path("max_score").isNull() || hits.path("max_score").isMissingNode()
                        ? (scores.isEmpty() ? 0 : scores.get(0))
                        : hits.path("max_score").asDouble(0),
                ids, scores, explain, root);
    }

    private static Map<String, Object> multiMatch(String type, Double tieBreaker) {
        Map<String, Object> mm = Json.map(
                "query", QUERY,
                "fields", FIELDS,
                "type", type,
                "minimum_should_match", "70%");
        if (tieBreaker != null) mm.put("tie_breaker", tieBreaker);
        return mm;
    }

    private static String enc(String id) {
        return URLEncoder.encode(id, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** explain 的描述可能很长，只留开头那句算法名 */
    private static String trim(String s) {
        if (s == null || s.isBlank()) return "—";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 52 ? t : t.substring(0, 51) + "…";
    }

    private static String score(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }

    /** 一次查询的全部证据：命中总数、最高分、Top-3 的 id 与分数、Top1 的 explain */
    private record Probe(long total, double maxScore, List<String> ids, List<Double> scores,
                         String explain, String explainRoot) {

        String at(int i) {
            if (i >= ids.size()) return "—";
            return shortId(ids.get(i)) + " @" + String.format(Locale.ROOT, "%.2f", scores.get(i));
        }

        /**
         * 自动 _id 有 20 个字符，三列就够把表格撑出视口。
         * 表格里只留前 6 位，完整值在 json 的 top3[].id 里 —— 排序和分数才是这一关的重点。
         */
        static String shortId(String id) {
            return id.length() <= 8 ? id : id.substring(0, 6) + "…";
        }

        String topId() {
            return ids.isEmpty() ? "—" : ids.get(0);
        }

        /** 同分同序才算「同一组结果」：只比分数会漏掉「换了 id 但分数一样」的情况 */
        boolean sameTop3(Probe other) {
            if (ids.size() != other.ids.size()) return false;
            for (int i = 0; i < ids.size(); i++) {
                if (!ids.get(i).equals(other.ids.get(i))) return false;
                if (Math.abs(scores.get(i) - other.scores.get(i)) > 0.005) return false;
            }
            return true;
        }

        Map<String, Object> json() {
            List<Map<String, Object>> top = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) {
                top.add(Json.map("rank", i + 1, "id", ids.get(i), "score", scores.get(i)));
            }
            return Json.map("total", total, "maxScore", maxScore, "top3", top,
                    "explain", explainRoot);
        }
    }
}
