package com.eslab.levels;

import com.eslab.lib.ProjectRoot;
import com.eslab.lib.EsClient;
import com.eslab.lib.EsException;
import com.eslab.lib.Json;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * M7-2 优化实验台：把「我觉得会更快」变成「实测快多少」
 *
 * 本关自身不做重活，只汇总四个 A/B 实验的入口与当前数据基线；
 * 每个实验由 /api/experiments/:id/run 独立执行，互不干扰。
 */
@Component
public class M72OptimizationLab implements LevelModule {

    private static final String PREFIX = "eslab-";
    private static final String V2 = "eslab-products-v2";
    private static final Pattern SIZE_PATTERN = Pattern.compile("^([\\d.]+)(b|kb|mb|gb|tb)$");

    private final EsClient es;
    private final ObjectMapper mapper;
    private final Path root;

    public M72OptimizationLab(EsClient es, ObjectMapper mapper,
                              ProjectRoot projectRoot) {
        this.es = es;
        this.mapper = mapper;
        this.root = projectRoot.path();
    }

    @Override
    public String id() {
        return "m7-2";
    }

    @Override
    public LevelResult run() throws Exception {
        JsonNode seedMeta = readSeedMeta();

        // @focus-start 实验基线：列出现有索引，再取 v2 的文档总数
        List<LabIndex> indices = listLabIndices();
        long count = 0;
        try {
            count = es.get("/" + V2 + "/_count").path("count").asLong();
        } catch (EsException ignored) {
            count = 0;
        }
        // @focus-end

        List<List<Object>> rows = new ArrayList<>();
        List<Object> indexRows = new ArrayList<>();
        for (LabIndex i : indices) {
            rows.add(TableData.row(i.index(), i.docs(), mb(i.storeBytes()), i.segments(), i.health()));
            indexRows.add(Json.map(
                    "index", i.index(),
                    "docs", i.docs(),
                    "storeBytes", i.storeBytes(),
                    "segments", i.segments(),
                    "health", i.health()));
        }

        String version = seedMeta == null ? "未知" : seedMeta.path("indexVersion").asText("未知");

        return LevelResult.builder("当前实验数据：" + count + " 条商品文档（" + indices.size()
                        + " 个索引）。四个优化实验都基于同一份数据做 A/B 对照，每个都给出中位数与样本值")
                .metrics(
                        new Metric("v2 文档数", count, null),
                        new Metric("实验索引数", indices.size(), null),
                        new Metric("数据版本", version, "索引重建后此值变化"),
                        new Metric("可做实验", 4, null))
                .table(new TableData(
                        List.of("索引", "文档数", "体积", "段数", "健康"), rows))
                .json(Json.map("seedMeta", seedMeta, "indices", indexRows))
                .notes(
                        "四个实验分别在页面上点\"运行\"执行，每个都会真实跑多轮采样，通常需要 3–15 秒。",
                        "结论只在本机、当前数据量下成立；换数据规模结论可能反转——这正是\"不要凭直觉优化\"的理由。",
                        "每个实验都会给出 caveat（结论的适用边界），请连 caveat 一起读。",
                        "数据版本号变化说明索引被重建过，历史对比数字不再可比。")
                .build();
    }

    /** 列出本项目创建的索引及文档数、体积、段数 */
    private List<LabIndex> listLabIndices() {
        List<LabIndex> rows = new ArrayList<>();
        JsonNode arr;
        try {
            arr = es.get("/_cat/indices/" + PREFIX
                    + "*?format=json&h=index,health,pri,rep,docs.count,store.size,segments.count");
        } catch (EsException e) {
            return rows;
        }
        if (!arr.isArray()) return rows;
        for (JsonNode r : arr) {
            rows.add(new LabIndex(
                    r.path("index").asText(),
                    r.path("docs.count").asLong(0),
                    parseSize(r.path("store.size").asText("")),
                    r.path("segments.count").asLong(0),
                    r.path("health").asText()));
        }
        return rows;
    }

    private JsonNode readSeedMeta() {
        Path seed = root.resolve("var/seed.json");
        try {
            if (!Files.isRegularFile(seed)) return null;
            return mapper.readTree(Files.readString(seed));
        } catch (Exception e) {
            return null;
        }
    }

    /** _cat 的体积是 "60.3mb" 这种带单位的字符串，换算成字节 */
    private static long parseSize(String s) {
        if (s == null || s.isBlank()) return 0;
        String v = s.trim().toLowerCase(Locale.ROOT);
        Matcher m = SIZE_PATTERN.matcher(v);
        if (!m.matches()) {
            try {
                return Math.round(Double.parseDouble(v));
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

    private static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", (double) bytes / 1024 / 1024);
    }

    private record LabIndex(String index, long docs, long storeBytes, long segments, String health) {
    }
}
