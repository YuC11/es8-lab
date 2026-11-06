package com.eslab.levels;

import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.BarChartData;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * M6-2 search_after + PIT：深分页的正确姿势
 *
 * search_after 用「上一页最后一条的排序值」作为下一页的起点，每页代价恒定；
 * PIT（Point In Time）锁住一个时间点的一致视图，避免翻页过程中写入导致结果漂移。
 */
@Component
public class M62SearchAfter implements LevelModule {

    private static final String V2 = "eslab-products-v2";
    private static final int SIZE = 100;
    private static final int PAGES = 10;

    private final EsClient es;

    public M62SearchAfter(EsClient es) {
        this.es = es;
    }

    @Override
    public String id() {
        return "m6-2";
    }

    @Override
    public LevelResult run() throws Exception {
        // ── A：from+size 直接跳到第 10 页 ──
        long startA = System.nanoTime();
        JsonNode a = es.post("/" + V2 + "/_search", Json.map(
                "from", 900,
                "size", SIZE,
                "sort", Json.list(Json.map("price", "asc"), Json.map("sku", "asc")),
                "_source", Json.list("sku")));
        double aMs = (System.nanoTime() - startA) / 1_000_000.0;

        // @focus-start 打开 PIT：固定一个一致视图，后续分页都在这个视图里翻
        JsonNode pit = es.post("/" + V2 + "/_pit?keep_alive=1m", null);
        // @focus-end
        String pitId = pit.path("id").asText();

        // @focus-start search_after 逐页翻：每页都带上上一页最后一条的 sort 值
        long startB = System.nanoTime();
        int got = 0;
        double bMs = 0;
        JsonNode searchAfter = null;
        try {
            for (int p = 0; p < PAGES; p++) {
                Map<String, Object> body = Json.map(
                        "size", SIZE,
                        "pit", Json.map("id", pitId, "keep_alive", "1m"),
                        "sort", Json.list(Json.map("price", "asc"), Json.map("_shard_doc", "asc")),
                        "_source", Json.list("sku"));
                if (searchAfter != null) body.put("search_after", searchAfter);

                JsonNode r = es.post("/_search", body);
                JsonNode hits = r.path("hits").path("hits");
                int n = hits.size();
                got += n;
                if (n < SIZE) break;
                searchAfter = hits.get(n - 1).path("sort");
            }
            bMs = (System.nanoTime() - startB) / 1_000_000.0;
        } finally {
            // PIT 持有段引用，必须显式关闭；否则 merge 与删除都会被它拖住
            closePit(pitId);
        }
        // @focus-end

        return LevelResult.builder("要第 10 页（第 " + (900 + 1) + "–" + (900 + SIZE) + " 条）：from+size 一次 "
                        + String.format(Locale.ROOT, "%.0f", aMs) + "ms；search_after 翻 " + PAGES + " 页共 "
                        + got + " 条只用 " + String.format(Locale.ROOT, "%.0f", bMs) + "ms；这次运行只记录了总耗时，不能据此断言每页代价恒定")
                .metrics(
                        new Metric("from+size 跳页", String.format(Locale.ROOT, "%.0f", aMs) + " ms",
                                "from=900 size=" + SIZE),
                        new Metric("search_after 翻 10 页", String.format(Locale.ROOT, "%.0f", bMs) + " ms",
                                "共 " + got + " 条"),
                        new Metric("每页平均", String.format(Locale.ROOT, "%.1f", bMs / PAGES) + " ms", null),
                        new Metric("结果一致性", "PIT 保证", "翻页期间写入不改变视图"))
                .chart(new BarChartData(chartItems(aMs, bMs), "ms",
                        "注意：这里比的是\"到达同一位置\"的总成本，search_after 是逐页累积"))
                .json(Json.map(
                        "pitId", pitId,
                        "fromSizeHits", a.path("hits").path("hits").size(),
                        "searchAfterHits", got))
                .notes(
                        "search_after 的前提是**稳定且唯一的排序**：本关用 price + _shard_doc（PIT 内的隐藏递增字段），保证不重不漏。",
                        "如果只用 price 排序，价格相同的文档顺序不稳定，翻页会出现重复或漏数据——这是最常见的实现错误。",
                        "PIT 有生命周期（keep_alive），不用时必须显式关闭，否则会持有段引用、阻碍 merge 与删除。",
                        "search_after 不能跳页（只能顺序翻）。要证明逐页成本是否稳定，需要记录多个页深的逐页耗时；本关不把单次总耗时外推成该结论。")
                .build();
    }

    private static List<BarChartData.Item> chartItems(double aMs, double bMs) {
        List<BarChartData.Item> items = new ArrayList<>();
        items.add(new BarChartData.Item("from+size 跳页", Math.round(aMs)));
        items.add(new BarChartData.Item("search_after " + PAGES + " 页", Math.round(bMs)));
        return items;
    }

    private void closePit(String pitId) {
        try {
            es.call("DELETE", "/_pit", Json.map("id", pitId));
        } catch (EsException ignored) {
            // 已被自动释放（keep_alive 到期）也视为清理完成
        }
    }
}
