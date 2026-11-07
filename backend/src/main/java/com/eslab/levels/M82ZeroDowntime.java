package com.eslab.levels;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabIndexes;
import com.eslab.lib.EsClient;
import com.eslab.lib.Json;
import com.eslab.model.AliasBaseline;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import com.eslab.model.Metric;
import com.eslab.model.TableData;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * m8-2：零停机改 Mapping。
 *
 * 核心不是 reindex，而是「一次请求同时 remove + add」——
 * 别名切换在集群状态里是一次原子更新，客户端不会看到「别名指向空索引」的中间态。
 *
 * 但真实上线还要三步才敢切：
 *   ① 校验：新索引条数必须等于旧索引，不等就停在这里，绝不切换；
 *   ② 抽样：从旧索引取 3 条 _id，去新索引 _mget，逐条比对 title/price；
 *   ③ 回滚：切换后立刻切回旧索引验证可读可写，再切到新索引作为最终状态。
 */
@Component
public class M82ZeroDowntime implements LevelModule {

    private final EsClient es;
    private final LabData lab;

    public M82ZeroDowntime(EsClient es, LabData lab) {
        this.es = es;
        this.lab = lab;
    }

    @Override
    public String id() {
        return "m8-2";
    }

    /** 本关起点固定 v3：旋转前后两个状态都可读，结果与执行顺序无关 */
    @Override
    public List<AliasBaseline> aliasBaselines() {
        return List.of(new AliasBaseline(LabIndexes.ALIAS, LabIndexes.V3));
    }

    @Override
    public LevelResult run() throws Exception {
        // @focus-start 新 Mapping 只能写进新索引：v4 多了 selling_point，改了 title.keyword.ignore_above
        // 定义与目标条数登记在 LabIndexes，条数不符会整条重建（不是补差额）
        lab.ensureByReindex(LabIndexes.V3);
        LabData.Ensure v4 = lab.ensureByReindex(LabIndexes.V4);
        // @focus-end

        // @focus-start 切换前必须停在 v3：上一关或手工操作可能把指针带到别处
        String priorTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        boolean repinned = !LabIndexes.V3.equals(priorTarget);
        if (repinned) {
            lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V3);
        }
        long beforeCount = lab.countQuietly(LabIndexes.ALIAS);
        String beforeTarget = lab.writeTargetOf(LabIndexes.ALIAS);
        // @focus-end

        // @focus-start ① 校验：条数不等就不切（这是上线前唯一真正的门禁）
        long v3Count = lab.countQuietly(LabIndexes.V3);
        long v4Count = lab.countQuietly(LabIndexes.V4);
        boolean validated = v3Count > 0 && v3Count == v4Count;
        // @focus-end

        // @focus-start ② 抽样：同一条 _id 在两边逐字段比对
        JsonNode sample = es.post("/" + LabIndexes.V3 + "/_search", Json.map(
                "size", 3, "sort", Json.list(Json.map("sku", "asc")),
                "_source", Json.list("sku", "title", "price")));
        List<String> ids = new ArrayList<>();
        Map<String, String> v3Sig = new LinkedHashMap<>();
        for (JsonNode h : sample.path("hits").path("hits")) {
            ids.add(h.path("_id").asText());
            v3Sig.put(h.path("_id").asText(), signature(h.path("_source")));
        }
        int sampled = 0;
        int matched = 0;
        if (!ids.isEmpty()) {
            JsonNode mget = es.post("/" + LabIndexes.V4 + "/_mget", Json.map("ids", ids));
            for (JsonNode d : mget.path("docs")) {
                sampled++;
                String id = d.path("_id").asText();
                if (d.path("found").asBoolean(false)
                        && v3Sig.getOrDefault(id, "").equals(signature(d.path("_source")))) {
                    matched++;
                }
            }
        }
        // @focus-end

        LabData.Swap swap = null;
        LabData.Swap rollback = null;
        LabData.Swap finalSwap = null;
        long afterCount = beforeCount;
        String afterTarget = beforeTarget;
        long rollbackCount = -1;

        if (validated) {
            // @focus-start ③ 切换 → 回滚 → 再切换：回滚路径必须真的走一遍，而不是写在文档里
            swap = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V4);
            afterCount = lab.countQuietly(LabIndexes.ALIAS);
            afterTarget = lab.writeTargetOf(LabIndexes.ALIAS);

            rollback = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V3);
            rollbackCount = lab.countQuietly(LabIndexes.ALIAS);

            finalSwap = lab.swapAlias(LabIndexes.ALIAS, LabIndexes.V4);
            afterCount = lab.countQuietly(LabIndexes.ALIAS);
            afterTarget = lab.writeTargetOf(LabIndexes.ALIAS);
            // @focus-end
        }

        List<List<Object>> table = new ArrayList<>();
        table.add(TableData.row("字段数", fieldCount(LabIndexes.V3), fieldCount(LabIndexes.V4)));
        table.add(TableData.row("title.keyword.ignore_above",
                ignoreAbove(LabIndexes.V3), ignoreAbove(LabIndexes.V4)));
        table.add(TableData.row("selling_point", hasField(LabIndexes.V3, "selling_point") ? "有" : "无",
                hasField(LabIndexes.V4, "selling_point") ? "有" : "无"));
        table.add(TableData.row("条数校验（切换门槛）", fmt(v3Count), fmt(v4Count)
                + (validated ? " · 相等，允许切" : " · 不等，拒绝切")));
        table.add(TableData.row("抽样 _id 比对", sampled + " 条", matched + " 条一致"));
        table.add(TableData.row("经别名命中", beforeCount < 0 ? "别名还不存在" : fmt(beforeCount),
                fmt(afterCount)));
        table.add(TableData.row("回滚到 v3 后", "—", rollbackCount < 0 ? "未执行" : fmt(rollbackCount) + " 条"));

        int actions = swap == null ? 0 : swap.actions();
        String actionHint = actions == 0 ? "校验未通过，未切换"
                : actions >= 2 ? "remove + add 同一次请求" : "已指向 v4，只需一次幂等 add";

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("swap", swap == null ? Map.of() : swap.raw());
        json.put("rollback", rollback == null ? Map.of() : rollback.raw());
        json.put("finalSwap", finalSwap == null ? Map.of() : finalSwap.raw());
        json.put("v4", v4);
        json.put("repinned", repinned);
        json.put("sample", Json.map("ids", ids, "sampled", sampled, "matched", matched));

        return LevelResult.builder("v4 建好、数据搬完，先校验条数（" + fmt(v3Count) + " = " + fmt(v4Count)
                        + "）、再抽样 " + sampled + " 条逐字段比对（" + matched + " 条一致），"
                        + (validated ? "然后用一次 /_aliases 把指针从 " + beforeTarget + " 换到 " + afterTarget
                        + "，并真的回滚了一次（回滚后仍读到 " + fmt(rollbackCount) + " 条）"
                        : "但校验不通过，本关停在 v3 不切换")
                        + (repinned ? "（进入本关时指针不在 v3，已先归位）" : ""))
                .metrics(
                        new Metric("v4 文档数", fmt(v4.docs()),
                                v4.shortfall() ? "源不足，未达 2 万" : (v4.created() ? "本次重建" : "已达标复用")),
                        new Metric("条数校验", validated ? "通过" : "未通过",
                                fmt(v3Count) + " vs " + fmt(v4Count) + "，不等则不切"),
                        new Metric("抽样比对", matched + " / " + sampled, "同一 _id 在两边逐字段比"),
                        new Metric("切换动作", actions + " 个", actionHint),
                        new Metric("回滚后命中", rollbackCount < 0 ? "—" : fmt(rollbackCount),
                                "切回 v3 仍可读，才敢再切 v4"),
                        new Metric("最终指向 / 命中", (afterTarget == null ? "无" : afterTarget) + " · " + fmt(afterCount),
                                "本关结束停在 v4，m8-4 与后续关卡不依赖它"))
                .table(TableData.of(List.of("对比项", "v3（旧）", "v4（新）"), table))
                .json(json)
                .notes(
                        "Mapping 的两个部分都不能原地改：新增字段不行，把 ignore_above 从 256 改成 128 也不行",
                        "同一次请求里的 remove + add 由集群状态更新一次生效，所以不存在「别名查不到数据」的瞬间",
                        "上线三步缺一不可：条数校验（不等就不切）、抽样比对（防止 reindex 搬错内容）、回滚预案（真的执行一遍，而不是写在文档里）",
                        "抽样用的是 _mget：先按 sku 升序从 v3 取 3 条 _id，再去 v4 取同 id 的 _source 逐字段比 —— 只比条数会漏掉「条数对但内容错」",
                        "本关的起点固定是 v3：进入时如果指针在别处（比如上一轮跑过 m9-6），会先归位再演示切换，"
                                + "所以表格里的「切换前」永远是真实值，不依赖你之前跑过哪些关")
                .build();
    }

    /** 抽样比对用的字段签名：只取 title 与 price，避免时间戳/评分之类的动态字段干扰 */
    private static String signature(JsonNode source) {
        return source.path("title").asText("") + "|" + source.path("price").asText("");
    }

    private int fieldCount(String index) {
        try {
            return mapping(index).path("properties").size();
        } catch (Exception e) {
            return -1;
        }
    }

    private int ignoreAbove(String index) {
        try {
            return mapping(index).path("properties").path("title")
                    .path("fields").path("keyword").path("ignore_above").asInt(0);
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean hasField(String index, String field) {
        try {
            return mapping(index).path("properties").has(field);
        } catch (Exception e) {
            return false;
        }
    }

    private JsonNode mapping(String index) {
        return es.get("/" + index + "/_mapping").path(index).path("mappings");
    }

    private static String fmt(long n) {
        return String.format(Locale.ROOT, "%,d", n);
    }
}
