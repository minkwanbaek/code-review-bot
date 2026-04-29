package com.reviewbot.convention;

import com.reviewbot.ai.OllamaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ConventionServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void learnFromText_ParsesAndPersistsConventions() throws IOException {
        Path conventionsFile = tempDir.resolve("conventions.json");
        ConventionService service = new ConventionService(
                conventionsFile.toString(),
                new OllamaClient("http://localhost:11434", "test-model", 5),
                false);

        Conventions conventions = service.learnFromText("""
                Use 4 spaces for indentation.
                Classes use PascalCase and methods use camelCase.
                Do not use `System.out.println`; use logger instead.
                """);

        assertThat(conventionsFile).exists();
        assertThat(conventions.getFormattingRules()).containsEntry("indentSpaces", 4);
        assertThat(conventions.getNamingPatterns()).containsEntry("class", "PascalCase");
        assertThat(conventions.getForbiddenPatterns())
                .extracting(Conventions.ForbiddenPattern::getPattern)
                .contains("System.out.println");
        assertThat(Files.readString(conventionsFile)).contains("System.out.println");
    }

    @Test
    void learnFromText_WhenAiEnabledUsesOllamaStructuredConventions() throws IOException {
        Path conventionsFile = tempDir.resolve("ai-conventions.json");
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
        server.expect(requestTo("http://localhost:11434/api/generate"))
                .andExpect(method(POST))
                .andRespond(withSuccess("""
                        {"response":"{\\\"importRules\\\":[{\\\"order\\\":[\\\"java\\\",\\\"javax\\\",\\\"org\\\",\\\"com\\\"],\\\"forbiddenImports\\\":[\\\"java.util.*\\\"],\\\"message\\\":\\\"Use explicit imports\\\"}],\\\"namingRules\\\":{\\\"class\\\":\\\"PascalCase\\\",\\\"method\\\":\\\"camelCase\\\",\\\"variable\\\":\\\"camelCase\\\",\\\"constant\\\":\\\"UPPER_SNAKE_CASE\\\",\\\"package\\\":\\\"lowercase\\\"},\\\"forbiddenPatterns\\\":[{\\\"pattern\\\":\\\"System.out.println\\\",\\\"message\\\":\\\"Use logger\\\",\\\"severity\\\":\\\"WARNING\\\"}],\\\"archRules\\\":[{\\\"fromLayer\\\":\\\"controller\\\",\\\"toLayer\\\":\\\"repository\\\",\\\"allowed\\\":false,\\\"severity\\\":\\\"ERROR\\\",\\\"message\\\":\\\"No direct repository access\\\"}]}"}
                        """, MediaType.APPLICATION_JSON));
        ConventionService service = new ConventionService(
                conventionsFile.toString(),
                new OllamaClient("http://localhost:11434", "deepseek-coder:1.3b", 5, restTemplate),
                false);

        Conventions conventions = service.learnFromText("""
                BZCC guide:
                Imports use java, javax, org, com order and java.util.* is forbidden.
                Classes use PascalCase, methods and variables use camelCase, constants use UPPER_SNAKE_CASE.
                Do not use System.out.println.
                Controller to repository dependency is forbidden.
                """, true);

        assertThat(conventions.getImportRules()).hasSize(1);
        assertThat(conventions.getNamingRules()).containsEntry("constant", "UPPER_SNAKE_CASE");
        assertThat(conventions.getArchRules()).hasSize(1);
        assertThat(conventions.getForbiddenPatterns().get(0).getPattern()).isEqualTo("System.out.println");
        assertThat(Files.readString(conventionsFile)).contains("importRules", "namingRules", "archRules");
        server.verify();
    }
}
