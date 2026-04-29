package com.reviewbot.web;

import com.reviewbot.features.dashboard.service.DashboardDataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false",
        "reviewbot.dashboard.data-dir=${java.io.tmpdir}/reviewbot-dashboard-test-${random.uuid}"
})
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DashboardDataService dashboardDataService;

    @Test
    void dashboard_RendersNotificationAndBatchControls() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Review queue")))
                .andExpect(content().string(containsString("Run selected reviews")))
                .andExpect(content().string(containsString("batch-progress-fill")));
    }

    @Test
    void startBatchReview_ReturnsCompletedProgress() throws Exception {
        Map<String, Object> pr = dashboardDataService.upsertPullRequest(Map.of(
                "number", 42,
                "title", "Test PR",
                "author", "minkwan",
                "provider", "github",
                "repo", "owner/repo",
                "state", "open"));

        mockMvc.perform(post("/api/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prIds\":[" + pr.get("id") + "]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                .andExpect(content().string(containsString("\"progress\":100")));
    }

    @Test
    void compareHistory_ReturnsNewAndResolvedViolations() throws Exception {
        Map<String, Object> baseline = dashboardDataService.addHistoryEntry(Map.of(
                "repo", "owner/repo",
                "prNumber", 42,
                "status", "FAILED",
                "violations", List.of(
                        violation("App.java", 10, "LOGGING", "Use logger"),
                        violation("App.java", 11, "LINE_LENGTH", "Line too long"))));
        Map<String, Object> target = dashboardDataService.addHistoryEntry(Map.of(
                "repo", "owner/repo",
                "prNumber", 42,
                "status", "FAILED",
                "violations", List.of(
                        violation("App.java", 10, "LOGGING", "Use logger"),
                        violation("App.java", 12, "INDENTATION", "Bad indent"))));

        mockMvc.perform(get("/api/history/compare")
                        .param("baselineId", String.valueOf(baseline.get("id")))
                        .param("targetId", String.valueOf(target.get("id"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"newCount\":1")))
                .andExpect(content().string(containsString("\"resolvedCount\":1")));
    }

    private Map<String, Object> violation(String file, int lineNumber, String rule, String message) {
        return Map.of(
                "file", file,
                "lineNumber", lineNumber,
                "rule", rule,
                "message", message);
    }
}
