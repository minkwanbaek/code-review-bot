package com.reviewbot.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "reviewbot.scheduler.enabled=false",
        "reviewbot.ai.enabled=false",
        "reviewbot.conventions.file=build/test-data/conventions-controller.json"
})
@AutoConfigureMockMvc
class ConventionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanConventionsFile() throws Exception {
        Files.deleteIfExists(Path.of("build/test-data/conventions-controller.json"));
    }

    @Test
    void conventionsPage_Renders() throws Exception {
        mockMvc.perform(get("/conventions"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Learn From Text")));
    }

    @Test
    void learnConventions_ParsesTextAndReturnsSavedRules() throws Exception {
        mockMvc.perform(post("/api/conventions/learn")
                        .contentType("application/json")
                        .content("""
                                {"text":"Do not use `System.out.println`. Use 4 spaces and camelCase methods."}
                                """))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"success\":true")))
                .andExpect(content().string(containsString("System.out.println")));
    }
}
