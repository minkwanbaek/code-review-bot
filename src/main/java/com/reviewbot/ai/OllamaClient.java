package com.reviewbot.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewbot.convention.Conventions;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ollama REST API 연동 클래스
 * - POST /api/generate (http://localhost:11434)
 * - 리뷰 프롬프트 생성 + Ollama 호출
 * - 리뷰 결과 파싱 (Violation 리스트로 변환)
 * - 타임아웃, 재시도, 에러 핸들링
 */
@Component
public class OllamaClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

    private String baseUrl;
    private String model;
    private int timeoutSeconds;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    /**
     * Creates an Ollama client from Spring configuration.
     *
     * @param baseUrl Ollama base URL
     * @param model model name
     * @param timeoutSeconds request timeout in seconds
     * @param restTemplateBuilder Spring REST template builder
     */
    @Autowired
    public OllamaClient(
            @Value("${reviewbot.ai.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${reviewbot.ai.ollama.model:deepseek-coder:1.3b}") String model,
            @Value("${reviewbot.ai.ollama.timeout-seconds:120}") int timeoutSeconds,
            RestTemplateBuilder restTemplateBuilder) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(timeoutSeconds))
                .setReadTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();
    }

    /**
     * Creates an Ollama client for direct use in tests or tools.
     *
     * @param baseUrl Ollama base URL
     * @param model model name
     * @param timeoutSeconds request timeout in seconds
     */
    public OllamaClient(String baseUrl, String model, int timeoutSeconds) {
        this(baseUrl, model, timeoutSeconds, new RestTemplate());
    }

    /**
     * Creates an Ollama client with an injected REST template.
     *
     * @param baseUrl Ollama base URL
     * @param model model name
     * @param timeoutSeconds request timeout in seconds
     * @param restTemplate REST client to use
     */
    public OllamaClient(String baseUrl, String model, int timeoutSeconds, RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.model = model;
        this.timeoutSeconds = timeoutSeconds;
        this.objectMapper = new ObjectMapper();
        this.restTemplate = restTemplate;
    }

    /**
     * 코드 리뷰 수행
     * @param diffContent diff 내용
     * @param conventions 컨벤션 정보
     * @return 리뷰 결과 (Violation 리스트)
     */
    public List<Violation> reviewCode(String diffContent, Conventions conventions) {
        String prompt = buildReviewPrompt(diffContent, conventions);
        
        try {
            String response = callOllama(prompt);
            return parseReviewResponse(response);
        } catch (Exception e) {
            log.error("Failed to review code with Ollama", e);
            return new ArrayList<>();
        }
    }

    /**
     * 자유 텍스트를 구조화된 Conventions 로 변환
     * @param text 자유 텍스트 (컨벤션 설명)
     * @return 구조화된 Conventions 객체
     */
    public Conventions analyzeConventions(String text) {
        String prompt = buildAnalyzeConventionsPrompt(text);
        
        try {
            String response = callOllama(prompt);
            return parseConventionsResponse(response);
        } catch (Exception e) {
            log.error("Failed to analyze conventions with Ollama", e);
            return new Conventions();
        }
    }

    /**
     * 리뷰 프롬프트 생성
     */
    private String buildReviewPrompt(String diffContent, Conventions conventions) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are a code review expert. Review the following code diff against the given conventions.\n\n");
        sb.append("## Code Diff:\n");
        sb.append(diffContent).append("\n\n");
        
        if (conventions != null) {
            sb.append("## Conventions:\n");
            
            if (conventions.getImportOrder() != null && !conventions.getImportOrder().isEmpty()) {
                sb.append("- Import Order: ").append(conventions.getImportOrder()).append("\n");
            }
            
            if (conventions.getNamingPatterns() != null && !conventions.getNamingPatterns().isEmpty()) {
                sb.append("- Naming Patterns: ").append(conventions.getNamingPatterns()).append("\n");
            }
            
            if (conventions.getFormattingRules() != null && !conventions.getFormattingRules().isEmpty()) {
                sb.append("- Formatting Rules: ").append(conventions.getFormattingRules()).append("\n");
            }
            
            if (conventions.getForbiddenPatterns() != null && !conventions.getForbiddenPatterns().isEmpty()) {
                sb.append("- Forbidden Patterns:\n");
                for (Conventions.ForbiddenPattern fp : conventions.getForbiddenPatterns()) {
                    sb.append("  - Pattern: ").append(fp.getPattern())
                      .append(", Description: ").append(fp.getDescription())
                      .append(", Severity: ").append(fp.getSeverity()).append("\n");
                }
            }
        }
        
        sb.append("\n## Output Format:\n");
        sb.append("Return violations in JSON format:\n");
        sb.append("[\n");
        sb.append("  {\n");
        sb.append("    \"severity\": \"ERROR|WARNING|INFO\",\n");
        sb.append("    \"rule\": \"rule name\",\n");
        sb.append("    \"message\": \"violation message\",\n");
        sb.append("    \"lineNumber\": 123\n");
        sb.append("  }\n");
        sb.append("]\n\n");
        sb.append("If no violations, return an empty array [].\n");
        
        return sb.toString();
    }

    /**
     * 컨벤션 분석 프롬프트 생성
     */
    private String buildAnalyzeConventionsPrompt(String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("Analyze the following guide text and extract code conventions into strict JSON only.\n");
        sb.append("Do not include markdown fences, explanations, or comments.\n\n");
        sb.append("## Input Text:\n");
        sb.append(text).append("\n\n");
        
        sb.append("## Output Format:\n");
        sb.append("Return exactly one JSON object with this structure:\n");
        sb.append("{\n");
        sb.append("  \"importRules\": [\n");
        sb.append("    {\n");
        sb.append("      \"order\": [\"java\", \"javax\", \"org\", \"com\"],\n");
        sb.append("      \"forbiddenImports\": [\"java.util.*\"],\n");
        sb.append("      \"message\": \"Use explicit imports\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"namingRules\": {\n");
        sb.append("    \"class\": \"PascalCase\",\n");
        sb.append("    \"method\": \"camelCase\",\n");
        sb.append("    \"variable\": \"camelCase\",\n");
        sb.append("    \"constant\": \"UPPER_SNAKE_CASE\",\n");
        sb.append("    \"package\": \"lowercase\"\n");
        sb.append("  },\n");
        sb.append("  \"importOrder\": [\"java\", \"javax\", \"org\", \"com\"],\n");
        sb.append("  \"namingPatterns\": {\"class\": \"PascalCase\", \"method\": \"camelCase\", \"variable\": \"camelCase\"},\n");
        sb.append("  \"formattingRules\": {\n");
        sb.append("    \"indentSpaces\": 4,\n");
        sb.append("    \"maxLineLength\": 120,\n");
        sb.append("    \"braceStyle\": \"sameLine\"\n");
        sb.append("  },\n");
        sb.append("  \"forbiddenPatterns\": [\n");
        sb.append("    {\n");
        sb.append("      \"pattern\": \"System.out.println\",\n");
        sb.append("      \"description\": \"Do not use print statements for logging\",\n");
        sb.append("      \"message\": \"Use a logger instead\",\n");
        sb.append("      \"severity\": \"WARNING\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"archRules\": [\n");
        sb.append("    {\n");
        sb.append("      \"fromLayer\": \"controller\",\n");
        sb.append("      \"toLayer\": \"repository\",\n");
        sb.append("      \"allowed\": false,\n");
        sb.append("      \"severity\": \"ERROR\",\n");
        sb.append("      \"message\": \"Controllers must not depend on repositories directly\"\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"dependencyRules\": [\n");
        sb.append("    {\n");
        sb.append("      \"layer\": \"controller\",\n");
        sb.append("      \"forbiddenDependencies\": [\"repository\"],\n");
        sb.append("      \"severity\": \"ERROR\",\n");
        sb.append("      \"message\": \"Controllers may depend only on services\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}\n\n");
        sb.append("Use empty arrays or objects for sections that are not present. Do not invent rules.\n");
        
        return sb.toString();
    }

    /**
     * Ollama API 호출
     */
    private String callOllama(String prompt) {
        String url = baseUrl + "/api/generate";
        
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        requestBody.put("options", Map.of(
            "temperature", 0.2,
            "num_predict", 2048
        ));
        
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);
        
        try {
            String response = restTemplate.postForObject(url, entity, String.class);
            if (response == null) {
                throw new RuntimeException("Ollama returned null response");
            }
            
            JsonNode rootNode = objectMapper.readTree(response);
            JsonNode responseNode = rootNode.get("response");
            
            if (responseNode == null || responseNode.asText().isEmpty()) {
                throw new RuntimeException("Ollama returned empty response");
            }
            
            return responseNode.asText();
        } catch (RestClientException e) {
            throw new RuntimeException("Failed to call Ollama API: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse Ollama response: " + e.getMessage(), e);
        }
    }

    /**
     * 리뷰 응답 파싱
     */
    private List<Violation> parseReviewResponse(String response) {
        List<Violation> violations = new ArrayList<>();
        
        try {
            // JSON 배열 추출 시도
            String jsonContent = extractJsonArray(response);
            if (jsonContent == null || jsonContent.isEmpty()) {
                return violations;
            }
            
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            
            if (rootNode.isArray()) {
                for (JsonNode node : rootNode) {
                    String severityStr = node.has("severity") ? node.get("severity").asText() : "INFO";
                    Severity severity = parseSeverity(severityStr);
                    String rule = node.has("rule") ? node.get("rule").asText() : "Unknown";
                    String message = node.has("message") ? node.get("message").asText() : "No message";
                    int lineNumber = node.has("lineNumber") ? node.get("lineNumber").asInt() : -1;
                    
                    Violation violation = new Violation(severity, rule, message, lineNumber);
                    violations.add(violation);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse review response", e);
        }
        
        return violations;
    }

    /**
     * 컨벤션 응답 파싱
     */
    private Conventions parseConventionsResponse(String response) {
        Conventions conventions = new Conventions();
        
        try {
            String jsonContent = extractJsonObject(response);
            if (jsonContent == null || jsonContent.isEmpty()) {
                return conventions;
            }
            
            JsonNode rootNode = objectMapper.readTree(jsonContent);

            if (rootNode.has("importRules") && rootNode.get("importRules").isArray()) {
                List<Conventions.ImportRule> importRules = new ArrayList<>();
                for (JsonNode node : rootNode.get("importRules")) {
                    Conventions.ImportRule rule = new Conventions.ImportRule();
                    rule.setOrder(readStringList(node.get("order")));
                    rule.setForbiddenImports(readStringList(node.get("forbiddenImports")));
                    rule.setMessage(readText(node, "message", ""));
                    importRules.add(rule);
                }
                conventions.setImportRules(importRules);
            }
            
            // Import order
            if (rootNode.has("importOrder") && rootNode.get("importOrder").isArray()) {
                conventions.setImportOrder(readStringList(rootNode.get("importOrder")));
            } else if (!conventions.getImportRules().isEmpty()) {
                conventions.setImportOrder(conventions.getImportRules().get(0).getOrder());
            }

            if (rootNode.has("namingRules") && rootNode.get("namingRules").isObject()) {
                Map<String, String> namingRules = new LinkedHashMap<>();
                JsonNode namingNode = rootNode.get("namingRules");
                for (String field : new String[]{"class", "method", "variable", "constant", "package"}) {
                    if (namingNode.has(field)) {
                        namingRules.put(field, namingNode.get(field).asText());
                    }
                }
                conventions.setNamingRules(namingRules);
            }
            
            // Naming patterns
            if (rootNode.has("namingPatterns") && rootNode.get("namingPatterns").isObject()) {
                Map<String, Object> namingPatterns = new LinkedHashMap<>();
                JsonNode namingNode = rootNode.get("namingPatterns");
                for (String field : new String[]{"class", "method", "variable", "constant", "package"}) {
                    if (namingNode.has(field)) {
                        namingPatterns.put(field, namingNode.get(field).asText());
                    }
                }
                conventions.setNamingPatterns(namingPatterns);
            } else if (!conventions.getNamingRules().isEmpty()) {
                conventions.setNamingPatterns(new LinkedHashMap<>(conventions.getNamingRules()));
            }
            
            // Formatting rules
            if (rootNode.has("formattingRules") && rootNode.get("formattingRules").isObject()) {
                Map<String, Object> formattingRules = new LinkedHashMap<>();
                JsonNode formattingNode = rootNode.get("formattingRules");
                for (String field : new String[]{"indentSpaces", "indentation", "maxLineLength", "braceStyle", "lineEndings"}) {
                    if (formattingNode.has(field)) {
                        if (formattingNode.get(field).isNumber()) {
                            String key = "indentation".equals(field) ? "indentSpaces" : field;
                            formattingRules.put(key, formattingNode.get(field).asInt());
                        } else {
                            formattingRules.put(field, formattingNode.get(field).asText());
                        }
                    }
                }
                conventions.setFormattingRules(formattingRules);
            }
            
            // Forbidden patterns
            if (rootNode.has("forbiddenPatterns") && rootNode.get("forbiddenPatterns").isArray()) {
                List<Conventions.ForbiddenPattern> forbiddenPatterns = new ArrayList<>();
                for (JsonNode node : rootNode.get("forbiddenPatterns")) {
                    String pattern = node.has("pattern") ? node.get("pattern").asText() : "";
                    String description = node.has("description") ? node.get("description").asText() : "";
                    String message = node.has("message") ? node.get("message").asText() : description;
                    String severity = node.has("severity") ? node.get("severity").asText() : "WARNING";
                    Conventions.ForbiddenPattern forbiddenPattern =
                            new Conventions.ForbiddenPattern(pattern, description, severity);
                    forbiddenPattern.setMessage(message);
                    forbiddenPatterns.add(forbiddenPattern);
                }
                conventions.setForbiddenPatterns(forbiddenPatterns);
            }

            if (rootNode.has("archRules") && rootNode.get("archRules").isArray()) {
                List<Conventions.ArchRule> archRules = new ArrayList<>();
                for (JsonNode node : rootNode.get("archRules")) {
                    Conventions.ArchRule rule = new Conventions.ArchRule();
                    rule.setFromLayer(readText(node, "fromLayer", readText(node, "sourceLayer", "")));
                    rule.setToLayer(readText(node, "toLayer", readText(node, "targetLayer", "")));
                    rule.setAllowed(!node.has("allowed") || node.get("allowed").asBoolean());
                    rule.setSeverity(readText(node, "severity", "ERROR"));
                    rule.setMessage(readText(node, "message", ""));
                    archRules.add(rule);
                }
                conventions.setArchRules(archRules);
            }

            if (rootNode.has("dependencyRules") && rootNode.get("dependencyRules").isArray()) {
                List<Conventions.DependencyRule> dependencyRules = new ArrayList<>();
                for (JsonNode node : rootNode.get("dependencyRules")) {
                    Conventions.DependencyRule rule = new Conventions.DependencyRule();
                    rule.setLayer(readText(node, "layer", ""));
                    rule.setForbiddenDependencies(readStringList(node.get("forbiddenDependencies")));
                    rule.setSeverity(readText(node, "severity", "ERROR"));
                    rule.setMessage(readText(node, "message", ""));
                    dependencyRules.add(rule);
                }
                conventions.setDependencyRules(dependencyRules);
            }
            
        } catch (Exception e) {
            log.error("Failed to parse conventions response", e);
        }
        
        return conventions;
    }

    private List<String> readStringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private String readText(JsonNode node, String field, String fallback) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return fallback;
    }

    /**
     * 응답에서 JSON 배열 추출
     */
    private String extractJsonArray(String response) {
        // ```json ... ``` 블록 찾기
        Pattern pattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // 직접 [ 로 시작하는 배열 찾기
        int startIndex = response.indexOf('[');
        if (startIndex >= 0) {
            int endIndex = response.lastIndexOf(']');
            if (endIndex > startIndex) {
                return response.substring(startIndex, endIndex + 1);
            }
        }
        
        return response.trim();
    }

    /**
     * 응답에서 JSON 객체 추출
     */
    private String extractJsonObject(String response) {
        // ```json ... ``` 블록 찾기
        Pattern pattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```");
        Matcher matcher = pattern.matcher(response);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        
        // 직접 { 로 시작하는 객체 찾기
        int startIndex = response.indexOf('{');
        if (startIndex >= 0) {
            int endIndex = response.lastIndexOf('}');
            if (endIndex > startIndex) {
                return response.substring(startIndex, endIndex + 1);
            }
        }
        
        return response.trim();
    }

    /**
     * Severity 문자열 파싱
     */
    private Severity parseSeverity(String severityStr) {
        if (severityStr == null) return Severity.INFO;
        
        switch (severityStr.toUpperCase()) {
            case "ERROR":
            case "CRITICAL":
                return Severity.ERROR;
            case "WARNING":
            case "WARN":
                return Severity.WARNING;
            default:
                return Severity.INFO;
        }
    }

    /**
     * Updates the model used for future Ollama requests.
     *
     * @param modelName model name
     */
    public void setModel(String modelName) {
        this.model = modelName;
    }

    /**
     * Updates the Ollama base URL used for future requests.
     *
     * @param url Ollama base URL
     */
    public void setBaseUrl(String url) {
        this.baseUrl = url;
    }

    /**
     * Returns the configured model name.
     *
     * @return model name
     */
    public String getModel() {
        return model;
    }

    /**
     * Returns the configured Ollama base URL.
     *
     * @return base URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * Returns the configured timeout.
     *
     * @return timeout in seconds
     */
    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    /**
     * Updates the timeout value stored by this client.
     *
     * @param timeoutSeconds timeout in seconds
     */
    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
