package com.eslab.lib;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 仓库根定位（唯一入口，全部路径解析都从这走）。
 *
 * 三种启动形态都要能拿到正确的仓库根：
 *   1. sh/*.sh 启动   —— 显式导出 ESLAB_ROOT，直接生效
 *   2. 仓库根裸跑 mvn —— user.dir 即仓库根
 *   3. backend/ 下裸跑 mvn spring-boot:run —— user.dir 是 backend/，
 *      此时上溯一级（backend/ 里有 front/ 吗？没有；../front 有）。
 *
 * 判据只看 front/ 目录是否存在（dist 可能尚未构建，不能拿它当依据）。
 */
@Component
public class ProjectRoot {

    private final Path root;

    public ProjectRoot(@Value("${eslab.root:${user.dir}}") String raw) {
        Path p = Path.of(raw).toAbsolutePath().normalize();
        if (!Files.isDirectory(p.resolve("front"))
                && Files.isDirectory(p.resolve("../front"))) {
            p = p.toAbsolutePath().getParent().normalize();
        }
        this.root = p;
    }

    public Path path() {
        return root;
    }
}
