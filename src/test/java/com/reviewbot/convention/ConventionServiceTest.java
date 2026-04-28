package com.reviewbot.convention;

import com.reviewbot.ai.OllamaClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
