package com.reviewbot.features.convention.service;

import com.reviewbot.convention.Conventions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.reviewbot.ai.OllamaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Loads, saves, and learns code convention rules.
 */
@Service
public class ConventionService {

    private static final Logger log = LoggerFactory.getLogger(ConventionService.class);

    private final ObjectMapper objectMapper;
    private final Path conventionsPath;
    private final OllamaClient ollamaClient;
    private final boolean aiEnabled;

    /**
     * Creates a convention service backed by a JSON file.
     *
     * @param conventionsFile path to conventions.json
     * @param ollamaClient Ollama client used when AI learning is enabled
     * @param aiEnabled whether AI convention learning should be attempted first
     */
    public ConventionService(
            @Value("${reviewbot.conventions.file:./conventions.json}") String conventionsFile,
            OllamaClient ollamaClient,
            @Value("${reviewbot.ai.enabled:false}") boolean aiEnabled) {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.conventionsPath = Path.of(conventionsFile);
        this.ollamaClient = ollamaClient;
        this.aiEnabled = aiEnabled;
    }

    /**
     * Loads conventions from disk, returning an empty convention set when no file exists.
     *
     * @return loaded conventions
     * @throws IOException when the file exists but cannot be read
     */
    public Conventions loadConventions() throws IOException {
        if (!Files.exists(conventionsPath)) {
            return new Conventions();
        }
        return objectMapper.readValue(conventionsPath.toFile(), Conventions.class);
    }

    /**
     * Saves conventions to disk as formatted JSON.
     *
     * @param conventions conventions to persist
     * @return saved conventions
     * @throws IOException when the file cannot be written
     */
    public Conventions saveConventions(Conventions conventions) throws IOException {
        Path parent = conventionsPath.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        objectMapper.writeValue(conventionsPath.toFile(), conventions);
        return conventions;
    }

    /**
     * Learns conventions from user supplied text and persists the merged result.
     *
     * @param text convention text
     * @return saved conventions after learning
     * @throws IOException when conventions cannot be loaded or saved
     */
    public Conventions learnFromText(String text) throws IOException {
        return learnFromText(text, aiEnabled);
    }

    /**
     * Learns conventions from user supplied text and persists the merged result.
     *
     * @param text convention text
     * @param requestAiEnabled whether this request should attempt AI learning
     * @return saved conventions after learning
     * @throws IOException when conventions cannot be loaded or saved
     */
    public Conventions learnFromText(String text, boolean requestAiEnabled) throws IOException {
        Conventions learned = null;
        if (requestAiEnabled) {
            learned = ollamaClient.analyzeConventions(text);
        }
        if (isEmpty(learned)) {
            learned = parseTextConventions(text);
        }

        Conventions existing = loadConventions();
        merge(existing, learned);
        return saveConventions(existing);
    }

    /**
     * Adds a forbidden pattern and persists the convention file.
     *
     * @param pattern pattern to add
     * @return saved conventions
     * @throws IOException when conventions cannot be loaded or saved
     */
    public Conventions addForbiddenPattern(Conventions.ForbiddenPattern pattern) throws IOException {
        Conventions conventions = loadConventions();
        conventions.addForbiddenPattern(pattern);
        return saveConventions(conventions);
    }

    /**
     * Updates a forbidden pattern by index and persists the convention file.
     *
     * @param index pattern index
     * @param pattern replacement pattern
     * @return saved conventions
     * @throws IOException when conventions cannot be loaded or saved
     */
    public Conventions updateForbiddenPattern(int index, Conventions.ForbiddenPattern pattern) throws IOException {
        Conventions conventions = loadConventions();
        if (index < 0 || index >= conventions.getForbiddenPatterns().size()) {
            throw new IllegalArgumentException("Invalid forbidden pattern index: " + index);
        }
        conventions.getForbiddenPatterns().set(index, pattern);
        return saveConventions(conventions);
    }

    /**
     * Deletes a forbidden pattern by index and persists the convention file.
     *
     * @param index pattern index
     * @return saved conventions
     * @throws IOException when conventions cannot be loaded or saved
     */
    public Conventions deleteForbiddenPattern(int index) throws IOException {
        Conventions conventions = loadConventions();
        if (index < 0 || index >= conventions.getForbiddenPatterns().size()) {
            throw new IllegalArgumentException("Invalid forbidden pattern index: " + index);
        }
        conventions.getForbiddenPatterns().remove(index);
        return saveConventions(conventions);
    }

    private Conventions parseTextConventions(String text) {
        Conventions conventions = new Conventions();
        String source = text == null ? "" : text;
        String lower = source.toLowerCase(Locale.ROOT);

        if (lower.contains("import")) {
            List<String> importOrder = extractImportOrder(source);
            conventions.setImportOrder(importOrder);
            conventions.setImportRules(List.of(new Conventions.ImportRule(
                    importOrder,
                    extractForbiddenImports(source),
                    "Import conventions from guide text")));
        }

        Map<String, String> namingRules = new LinkedHashMap<>();
        if (lower.contains("pascal")) {
            namingRules.put("class", "PascalCase");
        }
        if (lower.contains("camel")) {
            namingRules.put("method", "camelCase");
            namingRules.put("variable", "camelCase");
        }
        if (lower.contains("snake")) {
            namingRules.put("variable", "snake_case");
        }
        if (lower.contains("upper") && lower.contains("snake")) {
            namingRules.put("constant", "UPPER_SNAKE_CASE");
        }
        if (lower.contains("package") && (lower.contains("lower") || lower.contains("소문자"))) {
            namingRules.put("package", "lowercase");
        }
        if (!namingRules.isEmpty()) {
            conventions.setNamingRules(namingRules);
            conventions.setNamingPatterns(new LinkedHashMap<>(namingRules));
        }

        Map<String, Object> formatting = new LinkedHashMap<>();
        if (lower.contains("2 space") || lower.contains("2-space")) {
            formatting.put("indentSpaces", 2);
        } else if (lower.contains("4 space") || lower.contains("4-space") || lower.contains("indent")) {
            formatting.put("indentSpaces", 4);
        }
        if (lower.contains("120")) {
            formatting.put("maxLineLength", 120);
        }
        if (lower.contains("same line") || lower.contains("k&r")) {
            formatting.put("braceStyle", "sameLine");
        }
        if (!formatting.isEmpty()) {
            conventions.setFormattingRules(formatting);
        }

        conventions.setForbiddenPatterns(extractForbiddenPatterns(source));
        conventions.setArchRules(extractArchRules(source));
        conventions.setDependencyRules(extractDependencyRules(source));
        return conventions;
    }

    private List<String> extractImportOrder(String text) {
        List<String> order = new ArrayList<>();
        for (String token : List.of("java", "javax", "org", "com", "spring")) {
            if (text.toLowerCase(Locale.ROOT).contains(token) && !order.contains(token)) {
                order.add(token);
            }
        }
        return order;
    }

    private List<String> extractForbiddenImports(String text) {
        List<String> forbiddenImports = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("import") && (lower.contains("forbid") || lower.contains("do not")
                    || lower.contains("avoid") || lower.contains("금지"))) {
                String pattern = inferPattern(line);
                if (!pattern.isBlank()) {
                    forbiddenImports.add(pattern);
                }
            }
        }
        return forbiddenImports;
    }

    private List<Conventions.ForbiddenPattern> extractForbiddenPatterns(String text) {
        List<Conventions.ForbiddenPattern> patterns = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("forbid") || lower.contains("do not") || lower.contains("avoid") || lower.contains("금지")) {
                String pattern = inferPattern(line);
                if (!pattern.isBlank()) {
                    patterns.add(new Conventions.ForbiddenPattern(pattern, line.trim(), inferSeverity(lower)));
                }
            }
        }
        return patterns;
    }

    private List<Conventions.ArchRule> extractArchRules(String text) {
        List<Conventions.ArchRule> rules = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.contains("controller") && lower.contains("repository"))
                && (lower.contains("forbid") || lower.contains("do not") || lower.contains("must not")
                || lower.contains("forbidden") || lower.contains("금지"))) {
            rules.add(new Conventions.ArchRule(
                    "controller",
                    "repository",
                    false,
                    "ERROR",
                    "Controllers must not depend on repositories directly"));
        }
        return rules;
    }

    private List<Conventions.DependencyRule> extractDependencyRules(String text) {
        List<Conventions.DependencyRule> rules = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        if ((lower.contains("controller") && lower.contains("repository"))
                && (lower.contains("forbid") || lower.contains("do not") || lower.contains("must not")
                || lower.contains("forbidden") || lower.contains("금지"))) {
            rules.add(new Conventions.DependencyRule(
                    "controller",
                    List.of("repository"),
                    "ERROR",
                    "Controllers may depend only on services"));
        }
        return rules;
    }

    private String inferPattern(String line) {
        int tickStart = line.indexOf('`');
        int tickEnd = line.indexOf('`', tickStart + 1);
        if (tickStart >= 0 && tickEnd > tickStart) {
            return line.substring(tickStart + 1, tickEnd);
        }
        for (String token : line.split("\\s+")) {
            if (token.contains(".") || token.contains("(") || token.contains("@")) {
                return token.replaceAll("[,.;:]$", "");
            }
        }
        return line.trim();
    }

    private String inferSeverity(String lowerLine) {
        if (lowerLine.contains("error") || lowerLine.contains("must") || lowerLine.contains("금지")) {
            return "ERROR";
        }
        if (lowerLine.contains("info")) {
            return "INFO";
        }
        return "WARNING";
    }

    private void merge(Conventions target, Conventions source) {
        if (source.getImportOrder() != null && !source.getImportOrder().isEmpty()) {
            target.setImportOrder(source.getImportOrder());
        }
        if (source.getImportRules() != null && !source.getImportRules().isEmpty()) {
            target.setImportRules(source.getImportRules());
        }
        if (source.getNamingRules() != null && !source.getNamingRules().isEmpty()) {
            target.getNamingRules().putAll(source.getNamingRules());
        }
        if (source.getNamingPatterns() != null && !source.getNamingPatterns().isEmpty()) {
            target.getNamingPatterns().putAll(source.getNamingPatterns());
        }
        if (source.getFormattingRules() != null && !source.getFormattingRules().isEmpty()) {
            target.getFormattingRules().putAll(source.getFormattingRules());
        }
        if (source.getCommonPatterns() != null && !source.getCommonPatterns().isEmpty()) {
            target.setCommonPatterns(source.getCommonPatterns());
        }
        if (source.getForbiddenPatterns() != null && !source.getForbiddenPatterns().isEmpty()) {
            target.setForbiddenPatterns(source.getForbiddenPatterns());
        }
        if (source.getArchRules() != null && !source.getArchRules().isEmpty()) {
            target.setArchRules(source.getArchRules());
        }
        if (source.getDependencyRules() != null && !source.getDependencyRules().isEmpty()) {
            target.setDependencyRules(source.getDependencyRules());
        }
    }

    private boolean isEmpty(Conventions conventions) {
        return conventions == null
                || ((conventions.getImportOrder() == null || conventions.getImportOrder().isEmpty())
                && (conventions.getImportRules() == null || conventions.getImportRules().isEmpty())
                && (conventions.getNamingRules() == null || conventions.getNamingRules().isEmpty())
                && (conventions.getNamingPatterns() == null || conventions.getNamingPatterns().isEmpty())
                && (conventions.getFormattingRules() == null || conventions.getFormattingRules().isEmpty())
                && (conventions.getCommonPatterns() == null || conventions.getCommonPatterns().isEmpty())
                && (conventions.getForbiddenPatterns() == null || conventions.getForbiddenPatterns().isEmpty())
                && (conventions.getArchRules() == null || conventions.getArchRules().isEmpty())
                && (conventions.getDependencyRules() == null || conventions.getDependencyRules().isEmpty()));
    }
}
