package com.eslab.web;

import com.eslab.levels.support.LabData;
import com.eslab.levels.support.LabGate;
import com.eslab.model.LevelModule;
import com.eslab.model.LevelResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 关卡的统一执行入口。
 *
 * 在 module.run() 之前做两件事，都是「不写在关卡里、但每关都要」的：
 *   1. 检查 seed 是否正在跑（它会在另一个 JVM 里重建 v1/v2，进程内锁管不到）；
 *   2. 把本关声明的别名基线幂等归位，让「切换前指向哪儿」不再取决于执行顺序。
 */
@Service
public class LevelRunner {

    private final LabData lab;
    private final LabGate gate;
    private final Path seedLock;

    public LevelRunner(LabData lab, LabGate gate,
                       @Value("${eslab.root:${user.dir}}") String root) {
        this.lab = lab;
        this.gate = gate;
        this.seedLock = Path.of(root, "var", "seed.lock");
    }

    public record Outcome(LevelResult result, List<LabData.PinResult> pins, long elapsedMs) {
    }

    public Outcome run(LevelModule module) throws Exception {
        if (Files.exists(seedLock)) {
            throw new IllegalStateException(
                    "seed 正在重建 v1/v2（var/seed.lock 存在）—— 等它结束再运行关卡，否则数字不可信");
        }
        return gate.read(() -> {
            List<LabData.PinResult> pins = lab.pinAliases(module.aliasBaselines());
            long started = System.currentTimeMillis();
            LevelResult result = module.run();
            return new Outcome(result, pins, System.currentTimeMillis() - started);
        });
    }
}
