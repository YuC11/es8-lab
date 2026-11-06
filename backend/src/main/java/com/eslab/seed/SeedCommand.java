package com.eslab.seed;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 导入命令：只在带 --seed 启动时执行。
 *
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--seed=200000"
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--seed=20000"
 *
 * 带 --seed 时应用以非 Web 模式启动（见 EsLabApplication），不会占用 8787 端口。
 */
@Component
public class SeedCommand implements ApplicationRunner {

    private final SeedService seed;

    public SeedCommand(SeedService seed) {
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("seed")) return;

        List<String> values = args.getOptionValues("seed");
        int count = 200_000;
        if (values != null && !values.isEmpty() && !values.get(0).isBlank()) {
            count = Integer.parseInt(values.get(0));
        }
        String only = "both";
        if (args.containsOption("only")) {
            List<String> o = args.getOptionValues("only");
            if (o != null && !o.isEmpty()) only = o.get(0);
        }

        try {
            seed.seed(count, only);
            System.exit(0);
        } catch (Exception e) {
            System.err.println("\n✗ 导入失败：" + e.getMessage());
            System.exit(1);
        }
    }
}
