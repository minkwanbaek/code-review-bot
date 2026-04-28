package com.reviewbot.convention;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * US-2: Convention learner
 * 대상 repo 의 기존 코드를 분석해서 코드 스타일/컨벤션 베이스라인 자동 추출
 */
public class ConventionLearner {

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^import\\s+([\\w.]+);");
    private static final Pattern CLASS_PATTERN = Pattern.compile("(public|private|protected)?\\s*(class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("(public|private|protected)?\\s*[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\(");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("(public|private|protected)?\\s*[\\w<>\\[\\]]+\\s+(\\w+)\\s*;");

    /**
     * Repository 를 분석하여 conventions.json 생성
     */
    public Conventions analyzeRepository(Path repoPath) throws IOException {
        Conventions conventions = new Conventions();
        
        // Java 파일 수집
        List<Path> javaFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(repoPath)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/target/") && !p.toString().contains("/build/"))
                .forEach(javaFiles::add);
        }

        if (javaFiles.isEmpty()) {
            throw new IOException("No Java files found in repository");
        }

        // Import 순서 분석
        conventions.setImportOrder(analyzeImportOrder(javaFiles));
        
        // 네이밍 패턴 분석
        conventions.setNamingPatterns(analyzeNamingPatterns(javaFiles));
        
        // 포맷팅 규칙 분석
        conventions.setFormattingRules(analyzeFormattingRules(javaFiles));
        
        // Common 패턴 감지
        conventions.setCommonPatterns(analyzeCommonPatterns(javaFiles));

        return conventions;
    }

    /**
     * Import 순서 분석
     */
    private List<String> analyzeImportOrder(List<Path> javaFiles) throws IOException {
        Map<String, Integer> importCounts = new HashMap<>();
        
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                Matcher matcher = IMPORT_PATTERN.matcher(line.trim());
                if (matcher.matches()) {
                    String imported = matcher.group(1);
                    String category = categorizeImport(imported);
                    importCounts.put(category, importCounts.getOrDefault(category, 0) + 1);
                }
            }
        }
        
        // 빈도순으로 정렬
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(importCounts.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());
        
        List<String> order = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : sorted) {
            order.add(entry.getKey());
        }
        
        return order;
    }

    /**
     * Import 카테고리 분류
     */
    private String categorizeImport(String imported) {
        if (imported.startsWith("java.")) return "java";
        if (imported.startsWith("javax.")) return "javax";
        if (imported.startsWith("org.springframework.")) return "spring";
        if (imported.startsWith("org.")) return "org";
        if (imported.startsWith("com.")) return "com";
        return "other";
    }

    /**
     * 네이밍 패턴 분석
     */
    private Map<String, Object> analyzeNamingPatterns(List<Path> javaFiles) throws IOException {
        Map<String, Object> patterns = new HashMap<>();
        
        List<String> classNames = new ArrayList<>();
        List<String> methodNames = new ArrayList<>();
        List<String> variableNames = new ArrayList<>();
        
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            
            // Class names
            Matcher classMatcher = CLASS_PATTERN.matcher(content);
            while (classMatcher.find()) {
                classNames.add(classMatcher.group(3));
            }
            
            // Method names
            Matcher methodMatcher = METHOD_PATTERN.matcher(content);
            while (methodMatcher.find()) {
                methodNames.add(methodMatcher.group(2));
            }
            
            // Variable names
            Matcher varMatcher = VARIABLE_PATTERN.matcher(content);
            while (varMatcher.find()) {
                variableNames.add(varMatcher.group(2));
            }
        }
        
        patterns.put("classNames", classNames.subList(0, Math.min(10, classNames.size())));
        patterns.put("methodNames", methodNames.subList(0, Math.min(10, methodNames.size())));
        patterns.put("variableNames", variableNames.subList(0, Math.min(10, variableNames.size())));
        patterns.put("namingStyle", detectNamingStyle(classNames));
        
        return patterns;
    }

    /**
     * 네이밍 스타일 감지 (CamelCase, snake_case, etc.)
     */
    private String detectNamingStyle(List<String> names) {
        int camelCase = 0;
        int snakeCase = 0;
        
        for (String name : names) {
            if (name.matches("[a-z][a-zA-Z0-9]*")) camelCase++;
            if (name.contains("_")) snakeCase++;
        }
        
        return camelCase > snakeCase ? "camelCase" : "snake_case";
    }

    /**
     * 포맷팅 규칙 분석
     */
    private Map<String, Object> analyzeFormattingRules(List<Path> javaFiles) throws IOException {
        Map<String, Object> rules = new HashMap<>();
        
        int braceOnSameLine = 0;
        int braceOnNewLine = 0;
        int indentSpaces = 4; // default
        
        for (Path file : javaFiles) {
            List<String> lines = Files.readAllLines(file);
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                
                // Brace position
                if (line.trim().matches(".*\\{\\s*$")) {
                    braceOnNewLine++;
                } else if (line.trim().matches(".*\\{.*")) {
                    braceOnSameLine++;
                }
                
                // Indentation
                if (!line.trim().isEmpty()) {
                    int spaces = 0;
                    for (char c : line.toCharArray()) {
                        if (c == ' ') spaces++;
                        else break;
                    }
                    if (spaces > 0 && spaces % 2 == 0) {
                        indentSpaces = Math.min(indentSpaces, spaces);
                    }
                }
            }
        }
        
        rules.put("braceStyle", braceOnSameLine > braceOnNewLine ? "sameLine" : "newLine");
        rules.put("indentSpaces", indentSpaces);
        rules.put("useTabs", false);
        
        return rules;
    }

    /**
     * Common 패턴 감지
     */
    private List<String> analyzeCommonPatterns(List<Path> javaFiles) throws IOException {
        List<String> patterns = new ArrayList<>();
        
        int withLombok = 0;
        int withBuilder = 0;
        int withRecords = 0;
        int totalFiles = javaFiles.size();
        
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            
            if (content.contains("@Data") || content.contains("@Getter") || content.contains("@Setter")) {
                withLombok++;
            }
            if (content.contains(".builder()") || content.contains("@Builder")) {
                withBuilder++;
            }
            if (content.contains("record ")) {
                withRecords++;
            }
        }
        
        if (withLombok > totalFiles * 0.5) {
            patterns.add("Uses Lombok extensively");
        }
        if (withBuilder > totalFiles * 0.3) {
            patterns.add("Builder pattern commonly used");
        }
        if (withRecords > totalFiles * 0.2) {
            patterns.add("Java records used for DTOs");
        }
        
        return patterns;
    }

    /**
     * Conventions 를 JSON 으로 저장
     */
    public void saveConventions(Conventions conventions, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, toJson(conventions));
    }

    /**
     * Conventions 객체를 JSON 문자열로 변환
     */
    private String toJson(Conventions conventions) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        
        // Import order
        json.append("  \"importOrder\": [\n");
        List<String> importOrder = conventions.getImportOrder();
        for (int i = 0; i < importOrder.size(); i++) {
            json.append("    \"").append(importOrder.get(i)).append("\"");
            if (i < importOrder.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        
        // Naming patterns
        json.append("  \"namingPatterns\": {\n");
        Map<String, Object> naming = conventions.getNamingPatterns();
        json.append("    \"style\": \"").append(naming.getOrDefault("namingStyle", "camelCase")).append("\",\n");
        json.append("    \"classNames\": ").append(listToJson((List<?>) naming.get("classNames"))).append(",\n");
        json.append("    \"methodNames\": ").append(listToJson((List<?>) naming.get("methodNames"))).append(",\n");
        json.append("    \"variableNames\": ").append(listToJson((List<?>) naming.get("variableNames"))).append("\n");
        json.append("  },\n");
        
        // Formatting rules
        json.append("  \"formattingRules\": {\n");
        Map<String, Object> formatting = conventions.getFormattingRules();
        json.append("    \"braceStyle\": \"").append(formatting.getOrDefault("braceStyle", "sameLine")).append("\",\n");
        json.append("    \"indentSpaces\": ").append(formatting.getOrDefault("indentSpaces", 4)).append(",\n");
        json.append("    \"useTabs\": ").append(formatting.getOrDefault("useTabs", false)).append("\n");
        json.append("  },\n");
        
        // Common patterns
        json.append("  \"commonPatterns\": [\n");
        List<String> commonPatterns = conventions.getCommonPatterns();
        for (int i = 0; i < commonPatterns.size(); i++) {
            json.append("    \"").append(commonPatterns.get(i)).append("\"");
            if (i < commonPatterns.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        
        json.append("}\n");
        
        return json.toString();
    }

    private String listToJson(List<?> list) {
        if (list == null) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            sb.append("\"").append(list.get(i)).append("\"");
            if (i < list.size() - 1) sb.append(", ");
        }
        sb.append("]");
        return sb.toString();
    }
}
