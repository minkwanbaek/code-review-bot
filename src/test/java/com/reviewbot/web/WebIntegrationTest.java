package com.reviewbot.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false",
        "reviewbot.conventions.file=build/test-data/web-integration-conventions.json"
})
@AutoConfigureMockMvc
class WebIntegrationTest {

    private static final Path CONVENTIONS_FILE = Path.of("build/test-data/web-integration-conventions.json");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanConventionsFile() throws Exception {
        Files.deleteIfExists(CONVENTIONS_FILE);
    }

    @Test
    void allPages_ReturnOk() throws Exception {
        for (String page : new String[]{"/", "/conventions", "/settings", "/history", "/pr/101"}) {
            MvcResult result = mockMvc.perform(get(page))
                    .andExpect(status().isOk())
                    .andReturn();

            assertThat(result.getResponse().getContentAsString()).contains("<html");
        }
    }

    @Test
    void conventionCrud_WorksEndToEnd() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/conventions/forbidden-patterns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pattern": "System.out.println",
                                  "description": "Use logger instead",
                                  "severity": "WARNING"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode createdJson = readJson(created);
        assertThat(createdJson.path("success").asBoolean()).isTrue();
        assertThat(createdJson.at("/data/conventions/forbiddenPatterns/0/pattern").asText())
                .isEqualTo("System.out.println");

        MvcResult loaded = mockMvc.perform(get("/api/conventions"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode loadedJson = readJson(loaded);
        assertThat(loadedJson.at("/data/conventions/forbiddenPatterns/0/description").asText())
                .isEqualTo("Use logger instead");

        MvcResult updated = mockMvc.perform(put("/api/conventions/forbidden-patterns/0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "pattern": "Thread.sleep",
                                  "description": "Prefer awaitility in tests",
                                  "severity": "ERROR"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode updatedJson = readJson(updated);
        assertThat(updatedJson.at("/data/conventions/forbiddenPatterns/0/pattern").asText())
                .isEqualTo("Thread.sleep");
        assertThat(updatedJson.at("/data/conventions/forbiddenPatterns/0/severity").asText())
                .isEqualTo("ERROR");

        MvcResult deleted = mockMvc.perform(delete("/api/conventions/forbidden-patterns/0"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode deletedJson = readJson(deleted);
        assertThat(deletedJson.at("/data/conventions/forbiddenPatterns").size()).isZero();
        assertThat(Files.exists(CONVENTIONS_FILE)).isTrue();
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
