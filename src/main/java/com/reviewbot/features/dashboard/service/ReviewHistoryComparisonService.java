package com.reviewbot.features.dashboard.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares two review history entries and identifies violation changes.
 */
@Service
public class ReviewHistoryComparisonService {

    /**
     * Compares baseline and target review entries.
     *
     * @param baseline older review entry
     * @param target newer review entry
     * @return comparison payload containing new, resolved, and unchanged violations
     */
    public Map<String, Object> compare(Map<String, Object> baseline, Map<String, Object> target) {
        List<Map<String, Object>> baselineViolations = violationsFrom(baseline);
        List<Map<String, Object>> targetViolations = violationsFrom(target);

        Set<String> baselineKeys = keysFor(baselineViolations);
        Set<String> targetKeys = keysFor(targetViolations);

        List<Map<String, Object>> newViolations = targetViolations.stream()
                .filter(violation -> !baselineKeys.contains(signature(violation)))
                .toList();
        List<Map<String, Object>> resolvedViolations = baselineViolations.stream()
                .filter(violation -> !targetKeys.contains(signature(violation)))
                .toList();
        List<Map<String, Object>> unchangedViolations = targetViolations.stream()
                .filter(violation -> baselineKeys.contains(signature(violation)))
                .toList();

        Map<String, Object> comparison = new LinkedHashMap<>();
        comparison.put("baselineId", baseline.get("id"));
        comparison.put("targetId", target.get("id"));
        comparison.put("newViolations", newViolations);
        comparison.put("resolvedViolations", resolvedViolations);
        comparison.put("unchangedViolations", unchangedViolations);
        comparison.put("newCount", newViolations.size());
        comparison.put("resolvedCount", resolvedViolations.size());
        comparison.put("unchangedCount", unchangedViolations.size());
        return comparison;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> violationsFrom(Map<String, Object> review) {
        Object violations = review.get("violations");
        if (violations instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return new ArrayList<>();
    }

    private Set<String> keysFor(List<Map<String, Object>> violations) {
        Set<String> keys = new HashSet<>();
        violations.forEach(violation -> keys.add(signature(violation)));
        return keys;
    }

    private String signature(Map<String, Object> violation) {
        return String.join("|",
                String.valueOf(violation.getOrDefault("file", "")),
                String.valueOf(violation.getOrDefault("lineNumber", "")),
                String.valueOf(violation.getOrDefault("rule", "")),
                String.valueOf(violation.getOrDefault("message", "")));
    }
}
