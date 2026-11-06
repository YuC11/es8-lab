package com.eslab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

/**
 * ES Lab 后端入口。
 *
 * 设计上刻意很薄：
 *   1. 白名单代理 ES（浏览器不直连 9200，避免 CORS 与误删风险）
 *   2. 关卡编排（/api/levels/{id}/run）与 Java 源码回读（/api/levels/{id}/source）
 *   3. 实验编排（/api/experiments/{id}/run）
 *   4. 自由控制台与变体实验探针（/api/console、/api/probe）
 *
 * 不引入官方 Elasticsearch Java Client：学习项目里「请求长什么样」比「SDK 怎么包装」更重要，
 * 每关的 Java 实现就是一次真实 HTTP 调用，读代码即可对应到 curl。
 */
@SpringBootApplication
public class EsLabApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(EsLabApplication.class);

        // --seed 是导入命令，不需要起 Web 服务器（也避免与正在运行的后端抢 8787 端口）
        boolean seedMode = Arrays.stream(args)
                .anyMatch(a -> a.equals("--seed") || a.startsWith("--seed="));
        if (seedMode) app.setWebApplicationType(WebApplicationType.NONE);

        app.run(args);
    }
}
