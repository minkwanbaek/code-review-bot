package com.reviewbot.diff;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-1: Git diff analyzer 테스트
 */
class DiffAnalyzerTest {

    private DiffAnalyzer diffAnalyzer;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        diffAnalyzer = new DiffAnalyzer();
    }

    @Test
    void parseDiff_WithSimpleAddition() {
        String diffText = """
            diff --git a/Test.java b/Test.java
            index abc123..def456 100644
            --- a/Test.java
            +++ b/Test.java
            @@ -1,3 +1,4 @@
             public class Test {
                 public static void main(String[] args) {
            +        System.out.println("Hello");
                     return;
                 }
            """;

        StructuredDiff result = diffAnalyzer.parseDiff(diffText);

        assertThat(result.getFileCount()).isEqualTo(1);
        assertThat(result.getTotalChangeCount()).isGreaterThan(0);
        
        FileDiff file = result.getFiles().get(0);
        assertThat(file.getOldPath()).isEqualTo("Test.java");
        assertThat(file.getNewPath()).isEqualTo("Test.java");
        assertThat(file.getHunkCount()).isEqualTo(1);
    }

    @Test
    void parseDiff_WithMultipleFiles() {
        String diffText = """
            diff --git a/File1.java b/File1.java
            index abc..def 100644
            --- a/File1.java
            +++ b/File1.java
            @@ -1 +1 @@
            -old
            +new
            diff --git a/File2.java b/File2.java
            index 123..456 100644
            --- a/File2.java
            +++ b/File2.java
            @@ -1 +1,2 @@
             class File2 {}
            +// comment
            """;

        StructuredDiff result = diffAnalyzer.parseDiff(diffText);

        assertThat(result.getFileCount()).isEqualTo(2);
    }

    @Test
    void toJson_SerializesCorrectly() {
        String diffText = """
            diff --git a/Test.java b/Test.java
            --- a/Test.java
            +++ b/Test.java
            @@ -1 +1 @@
            -old
            +new
            """;

        StructuredDiff diff = diffAnalyzer.parseDiff(diffText);
        String json = diffAnalyzer.toJson(diff);

        assertThat(json).contains("\"files\"");
        assertThat(json).contains("\"Test.java\"");
        assertThat(json).contains("\"changes\"");
    }

    @Test
    void analyzeLocalDiff_WithInvalidRepo_ThrowsException() {
        Path invalidRepo = tempDir.resolve("nonexistent");

        assertThatThrownBy(() -> diffAnalyzer.analyzeLocalDiff(invalidRepo, "HEAD~1"))
                .isInstanceOf(IOException.class);
    }
}
