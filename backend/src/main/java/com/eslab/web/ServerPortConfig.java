package com.eslab.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * 端口固定为 eslab.port（默认 8787）。
 *
 * 为什么不直接用 server.port：Spring Boot 的宽松绑定会把环境变量 SERVER__PORT 也映射成
 * server.port，而某些 IDE / Agent 环境恰好注入了一个随机端口（本项目实测被注入 57355 并撞车）。
 * 用独立前缀 + WebServerFactoryCustomizer 在属性绑定之后再覆盖，能绕开这类污染。
 */
@Configuration
public class ServerPortConfig {

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    public WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> esLabPortCustomizer(
            @Value("${eslab.port:8787}") int port) {
        return factory -> factory.setPort(port);
    }
}
