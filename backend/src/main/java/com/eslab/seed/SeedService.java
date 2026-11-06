package com.eslab.seed;

import com.eslab.data.DatasetService;
import com.eslab.lib.EsClient;
import com.eslab.lib.ProjectRoot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令行导入（sh/seed.sh）：课程第 0 章的「快进」通道。
 *
 * 语义已经变了：它不再是「准备数据的唯一方式」，而是「跳过第 0 章，直接预置全量数据」。
 * 索引定义与写入逻辑都委托给 DatasetService，保证和页面里点出来的结果完全一致。
 *
 * 带 --seed 启动时以非 Web 模式运行（见 EsLabApplication），不会抢占 8787。
 */
@Service
public class SeedService {

    /** 关卡里普遍用 SeedService.V1/V2 指代两个实验索引，这里继续保留这两个别名 */
    public static final String PREFIX = DatasetService.PREFIX;
    public static final String V1 = DatasetService.V1;
    public static final String V2 = DatasetService.V2;

    private final EsClient es;
    private final ObjectMapper mapper;
    private final Path root;
    private final DatasetService dataset;

    public SeedService(EsClient es, ObjectMapper mapper, ProjectRoot projectRoot, DatasetService dataset) {
        this.es = es;
        this.mapper = mapper;
        this.root = projectRoot.path();
        this.dataset = dataset;
    }

    public void seed(int count, String only) throws Exception {
        JsonNode health = es.get("/_cluster/health");
        System.out.println("ES 集群状态：" + health.path("status").asText());

        boolean hasIk = false;
        JsonNode plugins = es.get("/_cat/plugins?format=json");
        if (plugins.isArray()) {
            for (JsonNode p : plugins) {
                if ("analysis-ik".equals(p.path("component").asText())) hasIk = true;
            }
        }
        if (!hasIk) {
            System.out.println("⚠ 未检测到 analysis-ik 插件，v2 索引的 ik_max_word 分词器会报错");
        }

        List<String> built = new ArrayList<>();
        if (!"v2".equals(only)) {
            System.out.println("\n[1/2] 构建 v1（全动态映射，单分片）…");
            build(DatasetService.V1, "v1", count);
            built.add(DatasetService.V1);
        }
        if (!"v1".equals(only)) {
            System.out.println("\n[2/2] 构建 v2（显式 Mapping，3 分片）…");
            build(DatasetService.V2, "v2", count);
            built.add(DatasetService.V2);
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("count", count);
        meta.put("seededAt", Instant.now().toString());
        meta.put("indexVersion", "v-" + System.currentTimeMillis());
        meta.put("indices", built);
        meta.put("mode", "seed-script");
        Path stateDir = root.resolve("var");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("seed.json"),
                mapper.writerWithDefaultPrettyPrinter().writeValueAsString(meta));

        // store.size 由文件系统统计，刷新后需要一点时间才反映真实体积
        Thread.sleep(1500);
        System.out.println("\n当前实验索引：");
        JsonNode rows = es.get("/_cat/indices/" + DatasetService.PREFIX
                + "*?format=json&h=index,pri,docs.count,store.size");
        for (JsonNode r : rows) {
            System.out.printf("  %-24s 分片 %s  文档 %s  体积 %s%n",
                    r.path("index").asText(), r.path("pri").asText(),
                    r.path("docs.count").asText(), r.path("store.size").asText());
        }
        System.out.println("\n完成。下一步：sh/backend.sh");
    }

    private void build(String index, String version, int count) throws Exception {
        if (dataset.exists(index)) {
            System.out.println("  · 已删除旧索引 " + index);
            dataset.deleteIfExists(index);
        }
        dataset.create(version);
        System.out.println("  · 已创建 " + index);

        DatasetService.BulkOutcome out = dataset.bulkLoad(index, count, 0, null);
        System.out.printf("  ✓ %s: %d 文档，耗时 %.1fs，平均 %d docs/s%n",
                index, out.docs(), out.ms() / 1000.0, out.rate());
    }
}
