package com.reviewbot.features.dashboard.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewHistoryComparisonServiceTest {

    private final ReviewHistoryComparisonService service = new ReviewHistoryComparisonService();

    @Test
    void compare_ReturnsNewResolvedAndUnchangedViolations() {
        Map<String, Object> baseline = Map.of(
                "id", 1L,
                "violations", List.of(
                        violation("App.java", 10, "LOGGING", "Use logger"),
                        violation("App.java", 11, "LINE_LENGTH", "Line too long")));
        Map<String, Object> target = Map.of(
                "id", 2L,
                "violations", List.of(
                        violation("App.java", 10, "LOGGING", "Use logger"),
                        violation("App.java", 12, "INDENTATION", "Bad indent")));

        Map<String, Object> comparison = service.compare(baseline, target);

        assertThat(comparison).containsEntry("newCount", 1);
        assertThat(comparison).containsEntry("resolvedCount", 1);
        assertThat(comparison).containsEntry("unchangedCount", 1);
        assertThat((List<Map<String, Object>>) comparison.get("newViolations"))
                .extracting(violation -> violation.get("rule"))
                .containsExactly("INDENTATION");
        assertThat((List<Map<String, Object>>) comparison.get("resolvedViolations"))
                .extracting(violation -> violation.get("rule"))
                .containsExactly("LINE_LENGTH");
    }

    private Map<String, Object> violation(String file, int lineNumber, String rule, String message) {
        return Map.of(
                "file", file,
                "lineNumber", lineNumber,
                "rule", rule,
                "message", message);
    }
}
