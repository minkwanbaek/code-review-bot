package com.reviewbot.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false"
})
@AutoConfigureMockMvc
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

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
        mockMvc.perform(post("/api/reviews/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prIds\":[101,102]}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"status\":\"COMPLETED\"")))
                .andExpect(content().string(containsString("\"progress\":100")));
    }

    @Test
    void compareHistory_ReturnsNewAndResolvedViolations() throws Exception {
        mockMvc.perform(get("/api/history/compare")
                        .param("baselineId", "1")
                        .param("targetId", "2"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("\"newCount\":1")))
                .andExpect(content().string(containsString("\"resolvedCount\":1")));
    }
}
