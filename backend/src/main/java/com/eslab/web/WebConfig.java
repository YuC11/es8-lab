package com.eslab.web;

import com.eslab.lib.ProjectRoot;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 前端产物（front/ 里 vite build → front/dist）由 Spring Boot 直接托管，
 * 单端口 8787 即可访问整个页面。
 *
 * 前后端是两个独立项目：后端启动时只「读」前端目录里的构建产物，不参与前端构建。
 * 没有 front/dist 时后端照常启动，只是页面 404 —— 此时先跑 sh/build.sh。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final Path root;

    public WebConfig(ProjectRoot projectRoot) {
        this.root = projectRoot.path();
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path dist = root.resolve("front/dist");
        if (Files.isDirectory(dist)) {
            String location = dist.toUri().toString();
            if (!location.endsWith("/")) location += "/";
            registry.addResourceHandler("/**")
                    .addResourceLocations(location)
                    .setCachePeriod(0);
            log.info("静态资源：{}", dist);
        } else {
            log.warn("未找到前端构建产物 {}，页面将 404。先执行 sh/build.sh", dist);
        }
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }
}
