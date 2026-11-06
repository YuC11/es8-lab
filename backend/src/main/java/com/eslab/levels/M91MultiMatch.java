package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

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
 * 实测细节（本关的一个隐藏知识点）：给 most_fields 加上 tie_breaker，
 * 它会退化成 best_fields 的算法 —— explain 里能直接看到。
 */
@Component
public class M91MultiMatch implements LevelModule {

    private static final String SEARCH = LabData.SEARCH;
    private static final String QUERY = "小米 降噪";

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

        // @focus-start 同一 query、同一组字段，只换 type
        Hit best = search("best_fields", true);
        Hit most = search("most_fields", false);
        Hit cross = search("cross_fields", false);
        Hit mostWithTb = search("most_fields", true);
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        rows.add(TableData.row("best_fields（tie_breaker 0.3）", fmt(best.total),
                score(best.maxScore), score(best.topScore), best.topTitle));
        rows.add(TableData.row("most_fields（无 tie_breaker）", fmt(most.total),
                score(most.maxScore), score(most.topScore), most.topTitle));
        rows.add(TableData.row("most_fields + tie_breaker", fmt(mostWithTb.total),
                score(mostWithTb.maxScore), score(mostWithTb.topScore), mostWithTb.topTitle));
        rows.add(TableData.row("cross_fields", fmt(cross.total),
                score(cross.maxScore), score(cross.topScore), cross.topTitle));

        boolean tbCollapses = Math.abs(mostWithTb.maxScore - best.maxScore) < 0.01;
        boolean sameTotal = best.total == most.total && most.total == cross.total;

        return LevelResult.builder("同一 query：most_fields 把各字段分数相加得 " + score(most.maxScore)
                        + "，best_fields 只取最高再补 0.3×其余得 " + score(best.maxScore)
                        + "；给 most_fields 也加 tie_breaker，它就退化成 best_fields（"
                        + (tbCollapses ? "实测完全相同" : "本次不同") + "）")
                .metrics(
                        new Metric("best_fields", score(best.maxScore), "最高分 + 0.3×其余字段"),
                        new Metric("most_fields", score(most.maxScore), "各字段分数直接相加"),
                        new Metric("cross_fields", score(cross.maxScore), "多字段当一个整体"),
                        new Metric("命中数是否相同", sameTotal ? "相同" : "不同",
                                fmt(best.total) + " 条，换 type 不影响命中集"))
                .table(TableData.of(List.of("模式", "命中", "最高分", "Top1 分数", "Top1 标题"), rows))
                .json(Map.of("query", QUERY, "fields", List.of("title^3", "description", "brand^2"),
                        "mostFieldsWithTieBreaker", mostWithTb.maxScore))
                .notes(
                        "title^3 表示标题的分数按 3 倍计：词出现在标题里，通常比出现在描述里更相关",
                        "most_fields 的分数天然偏高（同一批词在每个字段各算一次再加起来），不要拿它的分数与别的模式比",
                        "实测坑：most_fields 加了 tie_breaker 之后，explain 会显示「max plus 0.3 times others」，与 best_fields 完全一致 —— 这个参数把它切换成了另一套算法",
                        "cross_fields 适合「品牌在 brand、型号在 title」这类拆开的字段：它把多个字段当成一个大字段来匹配")
                .build();
    }

    /**
     * @param tieBreaker best_fields / most_fields 都接受，但 most_fields 一旦带上它就会退化成 best_fields
     */
    private Hit search(String type, boolean tieBreaker) {
        Map<String, Object> mm = Json.map(
                "query", QUERY,
                "fields", List.of("title^3", "description", "brand^2"),
                "type", type,
                "minimum_should_match", "70%");
        if (tieBreaker) mm.put("tie_breaker", 0.3);

        JsonNode d = es.post("/" + SEARCH + "/_search", Json.map(
                "size", 3,
                "track_total_hits", true,
                "query", Json.map("multi_match", mm)));

        JsonNode hits = d.path("hits");
        JsonNode arr = hits.path("hits");
        String id = arr.isEmpty() ? null : arr.get(0).path("_id").asText();
        String title = arr.isEmpty() ? "—" : arr.get(0).path("_source").path("title").asText("—");
        double topScore = arr.isEmpty() ? 0 : arr.get(0).path("_score").asDouble(0);
        return new Hit(hits.path("total").path("value").asLong(0),
                hits.path("max_score").asDouble(0), id, title, topScore);
    }

    private record Hit(long total, double maxScore, String topId, String topTitle, double topScore) {
    }

    private static String score(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
