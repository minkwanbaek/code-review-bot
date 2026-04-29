package com.reviewbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewbot.ai.OllamaClient;
import com.reviewbot.convention.Conventions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false",
        "reviewbot.conventions.file=build/test-data/ai-conventions-controller.json"
})
@AutoConfigureMockMvc
class AiConventionControllerTest {

    private static final Path CONVENTIONS_FILE = Path.of("build/test-data/ai-conventions-controller.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private OllamaClient ollamaClient;

    @BeforeEach
    void cleanConventionsFile() throws Exception {
        Files.deleteIfExists(CONVENTIONS_FILE);
    }

    @Test
    void learnConventionsWithAi_UsesOllamaEvenWhenGlobalAiDisabled() throws Exception {
        Conventions learned = new Conventions();
        learned.setImportOrder(List.of("java", "org", "com"));
        learned.setForbiddenPatterns(List.of(
                new Conventions.ForbiddenPattern("System.out.println", "Use logger", "WARNING")));
        when(ollamaClient.analyzeConventions(contains("Use logger"))).thenReturn(learned);

        MvcResult result = mockMvc.perform(post("/api/conventions/learn/ai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"Use logger instead of System.out.println"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(json.path("success").asBoolean()).isTrue();
        assertThat(json.at("/data/conventions/importOrder/0").asText()).isEqualTo("java");
        assertThat(json.at("/data/conventions/forbiddenPatterns/0/pattern").asText())
                .isEqualTo("System.out.println");
        verify(ollamaClient).analyzeConventions(contains("Use logger"));
    }
}
