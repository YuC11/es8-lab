package com.eslab.experiments;

import com.eslab.model.ExperimentModule;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 实验注册表 */
@Component
public class ExperimentRegistry {

    private final Map<String, ExperimentModule> byId = new LinkedHashMap<>();

    public ExperimentRegistry(Collection<ExperimentModule> experiments) {
        experiments.stream()
                .sorted((a, b) -> a.id().compareTo(b.id()))
                .forEach(e -> byId.put(e.id(), e));
    }

    public ExperimentModule get(String id) {
        return byId.get(id);
    }

    public Collection<ExperimentModule> all() {
        return byId.values();
    }

    public List<Map<String, String>> brief() {
        return byId.values().stream()
                .map(e -> Map.of("id", e.id(), "title", e.title(), "question", e.question()))
                .toList();
    }
}
