package com.eslab.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 实验环境接口：索引总览、复位、重建。
 *
 * 与 /api/console 的区别：控制台是「学员自己发 ES 请求」，这里是「平台自己管索引」，
 * 所以只接受注册表里的名字，不接受任何通配。
 */
@RestController
public class LabController {

    private final LabResetService service;

    public LabController(LabResetService service) {
        this.service = service;
    }

    /** 全部索引 + 别名 + 集群资产，带生命周期与目标条数 */
    @GetMapping("/api/lab/indexes")
    public ResponseEntity<?> indexes() {
        try {
            return ResponseEntity.ok(service.overview());
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("ok", false, "error", String.valueOf(e.getMessage())));
        }
    }

    /**
     * 复位：{"scope":"all|chapter|family|index","chapter":"m8","family":"eslab-goods",
     *        "index":"eslab-goods-v5","includeProducts":false,"dryRun":true}
     *
     * dryRun 默认 true —— 前端第一次点只返回「会删什么」，第二次才真删。
     */
    @PostMapping("/api/lab/reset")
    public ResponseEntity<?> reset(@RequestBody(required = false) Map<String, Object> req) {
        try {
            return ResponseEntity.ok(service.reset(req == null ? Map.of() : req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(bad(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(bad(String.valueOf(e.getMessage())));
        }
    }

    /**
     * 重建：{"scope":"baseline|chapter|index","chapter":"m8","index":"eslab-goods-v3","count":20000}
     *
     * baseline 会删掉并重建 v1/v2；chapter/index 只重建登记的派生索引，
     * 一次性索引（DROP_AFTER_RUN）与「每关自己清空」的索引不会被碰。
     */
    @PostMapping("/api/lab/rebuild")
    public ResponseEntity<?> rebuild(@RequestBody(required = false) Map<String, Object> req) {
        try {
            return ResponseEntity.ok(service.rebuild(req == null ? Map.of() : req));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(bad(e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(bad(String.valueOf(e.getMessage())));
        }
    }

    private static Map<String, Object> bad(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message);
        return out;
    }
}
