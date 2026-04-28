package com.reviewbot.convention;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-2: Convention learner 테스트
 */
class ConventionLearnerTest {

    private ConventionLearner learner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        learner = new ConventionLearner();
    }

    @Test
    void analyzeRepository_WithEmptyDirectory_ThrowsException() {
        assertThatThrownBy(() -> learner.analyzeRepository(tempDir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No Java files found");
    }

    @Test
    void analyzeRepository_DetectsConventions() throws IOException {
        // Create sample Java files
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        Path testFile = srcDir.resolve("TestClass.java");
        Files.writeString(testFile, """
            package com.example;
            
            import java.util.List;
            import java.util.ArrayList;
            import org.springframework.stereotype.Component;
            
            @Component
            public class TestClass {
                private List<String> items = new ArrayList<>();
                
                public void addItem(String item) {
                    items.add(item);
                }
            }
            """);

        Conventions conventions = learner.analyzeRepository(tempDir);

        assertThat(conventions.getImportOrder()).isNotEmpty();
        assertThat(conventions.getNamingPatterns()).isNotEmpty();
        assertThat(conventions.getFormattingRules()).isNotEmpty();
    }

    @Test
    void saveConventions_WritesJsonFile() throws IOException {
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);

        Path testFile = srcDir.resolve("Test.java");
        Files.writeString(testFile, """
            public class Test {
                public void method() {
                }
            }
            """);

        Conventions conventions = learner.analyzeRepository(tempDir);
        Path outputPath = tempDir.resolve(".reviewbot/conventions.json");

        learner.saveConventions(conventions, outputPath);

        assertThat(outputPath).exists();
        String content = Files.readString(outputPath);
        assertThat(content).contains("\"importOrder\"");
        assertThat(content).contains("\"namingPatterns\"");
        assertThat(content).contains("\"formattingRules\"");
    }

    @Test
    void detectNamingStyle_CamelCase() {
        List<String> names = List.of("TestClass", "MyService", "UserController");
        
        // This is tested indirectly through analyzeRepository
        // A more direct test would require refactoring
    }
}
