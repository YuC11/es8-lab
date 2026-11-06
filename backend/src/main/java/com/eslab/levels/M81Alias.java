package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.lib.EsClient;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m8-1：别名是什么。
 *
 * 这一关只做一件事：把「应用看到的索引名」和「真实的物理索引」拆开。
 * 后面的零停机、同义词上线都建立在它上面 —— 没有别名，任何 mapping 变更都要停机。
 */
@Component
public class M81Alias implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M81Alias(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-1";
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 建 v3 并把 v2 的数据搬过去（v3 与 v2 同构，只是换了个名字）
        boolean created = false;
        if (!lab.exists(LabData.V3)) {
            es.put("/" + LabData.V3, lab.v3Definition());
            created = true;
        }
        long have = Math.max(0, lab.count(LabData.V3));
        long reindexed = 0;
        long reindexMs = 0;
        if (have < LabData.DOCS) {
            LabData.Reindex r = lab.reindex(LabData.SOURCE, LabData.V3, LabData.DOCS, null);
            reindexed = r.created();
            reindexMs = r.took();
            lab.refresh(LabData.V3);
        }
        // @focus-end

        // @focus-start 把别名挂到 v3：应用此后只认 eslab-goods
        List<Map<String, Object>> before = lab.aliasRows(LabData.ALIAS);
        boolean aliasCreated = false;
        if (before.isEmpty()) {
            aliasCreated = lab.swapAlias(LabData.ALIAS, LabData.V3).acknowledged();
        }
        // @focus-end

        List<Map<String, Object>> rows = lab.aliasRows(LabData.ALIAS);
        String target = lab.writeTargetOf(LabData.ALIAS);
        long viaAlias = countOf(LabData.ALIAS);
        long direct = lab.count(LabData.V3);

        List<List<Object>> table = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            table.add(TableData.row(
                    row.get("alias"),
                    row.get("index"),
                    Boolean.TRUE.equals(row.get("writeIndex")) ? "是" : "否"));
        }

        return LevelResult.builder("别名 " + LabData.ALIAS + " 现在指向 " + target
                        + "；应用只认别名，换索引时不用改代码")
                .metrics(
                        new Metric("v3 文档数", fmt(direct), created ? "本次新建并导入" : "复用已有索引"),
                        new Metric("经别名查询", fmt(viaAlias), "应当与直连 v3 完全一致"),
                        new Metric("reindex 写入", reindexed > 0 ? fmt(reindexed) + " 条" : "本次跳过",
                                reindexed > 0 ? reindexMs + "ms" : "v3 已达标"),
                        new Metric("别名指向", target == null ? "无" : target,
                                aliasCreated ? "本次刚创建" : "已存在，未改指向"))
                .table(TableData.of(List.of("alias", "index", "is_write_index"), table))
                .json(Map.of("created", created, "reindexed", reindexed, "aliases", rows))
                .notes(
                        "别名不是索引的副本，只是一层指向关系：写 " + LabData.ALIAS
                                + " 的文档会落到 " + target + " 上",
                        "本关刻意不把别名强行改回 v3 —— 如果你已经跑过后面的关，它可能指向 v4 或 v6，那是正常的",
                        "真正的价值在下一关：一次请求同时完成「移除旧指向 + 添加新指向」，客户端不会看到中间态")
                .build();
    }

    private long countOf(String indexOrAlias) {
        try {
            return es.get("/" + indexOrAlias + "/_count").path("count").asLong(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
