package com.reviewbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewbot.ai.OllamaClient;
import com.reviewbot.convention.Conventions;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false"
})
@AutoConfigureMockMvc
class ReviewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OllamaClient ollamaClient;

    @Test
    void reviewDiff_ReturnsRunnerViolationsWithoutAiByDefault() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/review/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "diffText": "diff --git a/src/main/java/com/example/App.java b/src/main/java/com/example/App.java\\n@@ -1,3 +1,4 @@\\n public class App {\\n+   System.out.println(\\\"hello\\\");\\n }\\n",
                                  "conventions": {
                                    "formattingRules": {"indentSpaces": 4},
                                    "commonPatterns": ["System.out.println"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = readJson(result);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.at("/data/totalViolations").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(json.at("/data/reviewResult/fileReviews/0/filePath").asText())
                .isEqualTo("src/main/java/com/example/App.java");
        assertThat(json.at("/data/reviewResult/fileReviews/0/violations").size())
                .isGreaterThanOrEqualTo(1);
        assertThat(json.at("/data/aiViolations").size()).isZero();
        verify(ollamaClient, never()).reviewCode(any(), any());
    }

    @Test
    void reviewDiff_AppendsAiViolationsWhenRequested() throws Exception {
        when(ollamaClient.reviewCode(contains("System.out.println"), any(Conventions.class)))
                .thenReturn(List.of(new Violation(Severity.WARNING, "AI_LOGGING", "Use logger", 2)));

        MvcResult result = mockMvc.perform(post("/api/review/diff")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "aiEnabled": true,
                                  "diffText": "diff --git a/src/App.java b/src/App.java\\n@@ -1,2 +1,3 @@\\n class App {\\n+    System.out.println(\\\"hello\\\");\\n }\\n",
                                  "conventions": {}
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = readJson(result);
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.at("/data/aiViolations/0/rule").asText()).isEqualTo("AI_LOGGING");
        assertThat(json.at("/data/totalViolations").asInt()).isGreaterThanOrEqualTo(1);
        verify(ollamaClient).reviewCode(contains("System.out.println"), any(Conventions.class));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
