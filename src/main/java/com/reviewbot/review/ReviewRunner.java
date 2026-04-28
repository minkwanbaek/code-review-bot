package com.reviewbot.review;

import com.reviewbot.diff.*;
import com.reviewbot.convention.Conventions;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US-3: Review runner
 * 수집된 convention 기준으로 diff 검사. 위반 항목을 파일별/라인별로 리포트
 */
public class ReviewRunner {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Diff 와 conventions 를 기반으로 리뷰 수행
     * 
     * @param diff 구조화된 diff 정보
     * @param conventions 코드 컨벤션 정보
     * @return 리뷰 결과 (위반 항목 포함)
     */
    public ReviewResult review(StructuredDiff diff, Conventions conventions) {
        ReviewResult result = new ReviewResult();
        
        for (FileDiff file : diff.getFiles()) {
            FileReview fileReview = new FileReview(file.getNewPath());
            int currentLineNumber = 0;
            
            for (Hunk hunk : file.getHunks()) {
                for (Change change : hunk.getChanges()) {
                    // 라인번호 업데이트
                    if (change.getLineNumberNew() > 0) {
                        currentLineNumber = change.getLineNumberNew();
                    }
                    
                    if (change.getType() == ChangeType.ADDITION) {
                        List<Violation> violations = checkViolations(change, conventions, currentLineNumber);
                        for (Violation violation : violations) {
                            violation.setLineNumber(currentLineNumber);
                            fileReview.addViolation(violation);
                        }
                    }
                }
            }
            
            if (!fileReview.getViolations().isEmpty()) {
                result.addFileReview(fileReview);
            }
        }
        
        return result;
    }

    /**
     * 개별 변경사항에 대한 위반 체크
     * 
     * @param change 변경사항
     * @param conventions 코드 컨벤션
     * @param lineNumber 현재 라인번호
     * @return 위반 항목 리스트
     */
    private List<Violation> checkViolations(Change change, Conventions conventions, int lineNumber) {
        List<Violation> violations = new ArrayList<>();
        String content = change.getContent();
        
        // Import 순서 위반 체크
        if (content.startsWith("import ")) {
            Violation importViolation = checkImportOrder(content, conventions, lineNumber);
            if (importViolation != null) {
                violations.add(importViolation);
            }
        }
        
        // 네이밍 패턴 위반 체크
        Violation namingViolation = checkNamingPattern(content, conventions, lineNumber);
        if (namingViolation != null) {
            violations.add(namingViolation);
        }
        
        // 포맷팅 규칙 위반 체크
        Violation formattingViolation = checkFormattingRule(content, conventions, lineNumber);
        if (formattingViolation != null) {
            violations.add(formattingViolation);
        }
        
        // 공통 패턴 위반 체크
        Violation commonPatternViolation = checkCommonPatterns(content, conventions, lineNumber);
        if (commonPatternViolation != null) {
            violations.add(commonPatternViolation);
        }
        
        return violations;
    }

    /**
     * Import 순서 위반 체크
     * 
     * @param importLine import 문장
     * @param conventions 컨벤션 정보
     * @param lineNumber 라인번호
     * @return 위반 항목 (없으면 null)
     */
    private Violation checkImportOrder(String importLine, Conventions conventions, int lineNumber) {
        List<String> importOrder = conventions.getImportOrder();
        if (importOrder.isEmpty()) return null;
        
        // 실제 구현에서는 import 그룹 순서를 추적해야 함
        // 여기서는 간단한 예시만 구현
        String packageName = extractPackageName(importLine);
        if (packageName == null) return null;
        
        // Java 표준 import 는 java. 로 시작해야 함
        if (packageName.startsWith("java.") || packageName.startsWith("javax.")) {
            return null; // 정상
        }
        
        // org.springframework 등 프레임워크 import
        if (packageName.startsWith("org.")) {
            return null; // 정상
        }
        
        // 커스텀 패키지 - 추가 검증 로직 필요
        return null;
    }
    
    /**
     * Import 문장에서 패키지명 추출
     */
    private String extractPackageName(String importLine) {
        Pattern pattern = Pattern.compile("import\\s+([\\w.]+);");
        Matcher matcher = pattern.matcher(importLine);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 네이밍 패턴 위반 체크
     * 
     * @param content 코드 내용
     * @param conventions 컨벤션 정보
     * @param lineNumber 라인번호
     * @return 위반 항목 (없으면 null)
     */
    @SuppressWarnings("unchecked")
    private Violation checkNamingPattern(String content, Conventions conventions, int lineNumber) {
        Map<String, Object> namingPatterns = conventions.getNamingPatterns();
        String style = (String) namingPatterns.getOrDefault("namingStyle", "camelCase");
        
        // Class/Interface/Enum 선언 체크
        Pattern classPattern = Pattern.compile("\\b(class|interface|enum)\\s+([A-Za-z0-9_]+)");
        Matcher classMatcher = classPattern.matcher(content);
        if (classMatcher.find()) {
            String className = classMatcher.group(2);
            // CamelCase: 대문자로 시작해야 함
            if ("camelCase".equals(style) && !className.matches("^[A-Z][a-zA-Z0-9]*$")) {
                return new Violation(
                    Severity.WARNING,
                    "NAMING_CONVENTION",
                    String.format("Class name '%s' should follow camelCase convention (start with uppercase)", className),
                    lineNumber
                );
            }
        }
        
        // Method declaration 체크
        Pattern methodPattern = Pattern.compile("\\b(public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\]]+\\s+([a-z][a-zA-Z0-9]*)\\s*\\(");
        Matcher methodMatcher = methodPattern.matcher(content);
        if (methodMatcher.find()) {
            String methodName = methodMatcher.group(2);
            // 메서드는 소문자로 시작하는 camelCase 여야 함
            if (!methodName.matches("^[a-z][a-zA-Z0-9]*$")) {
                return new Violation(
                    Severity.WARNING,
                    "NAMING_CONVENTION",
                    String.format("Method name '%s' should start with lowercase letter", methodName),
                    lineNumber
                );
            }
        }
        
        // Constant declaration 체크 (final static)
        Pattern constantPattern = Pattern.compile("\\b(final|static)\\s+(?:final|static)*\\s*[\\w<>\\[\\]]+\\s+([A-Z_][A-Z0-9_]*)\\s*=");
        Matcher constantMatcher = constantPattern.matcher(content);
        if (constantMatcher.find()) {
            // 상수는 대문자 + 언더스코어여야 함 - 정상
            return null;
        }
        
        // Variable declaration 체크
        Pattern varPattern = Pattern.compile("\\b(private|protected|public)?\\s*(?:final\\s+)?[\\w<>\\[\\]]+\\s+([a-z][a-zA-Z0-9]*)\\s*=");
        Matcher varMatcher = varPattern.matcher(content);
        if (varMatcher.find()) {
            String varName = varMatcher.group(2);
            // 변수는 소문자로 시작하는 camelCase
            if (!varName.matches("^[a-z][a-zA-Z0-9]*$")) {
                return new Violation(
                    Severity.INFO,
                    "NAMING_CONVENTION",
                    String.format("Variable name '%s' should follow camelCase convention", varName),
                    lineNumber
                );
            }
        }
        
        return null;
    }

    /**
     * 포맷팅 규칙 위반 체크
     * 
     * @param content 코드 내용
     * @param conventions 컨벤션 정보
     * @param lineNumber 라인번호
     * @return 위반 항목 (없으면 null)
     */
    @SuppressWarnings("unchecked")
    private Violation checkFormattingRule(String content, Conventions conventions, int lineNumber) {
        Map<String, Object> formattingRules = conventions.getFormattingRules();
        Integer indentSpaces = (Integer) formattingRules.getOrDefault("indentSpaces", 4);
        String braceStyle = (String) formattingRules.getOrDefault("braceStyle", "sameLine");
        Integer maxLineLength = (Integer) formattingRules.getOrDefault("maxLineLength", 120);
        
        // 들여쓰기 체크 (빈 줄이나 주석 제외)
        if (!content.trim().isEmpty() && !content.trim().startsWith("//") && !content.trim().startsWith("/*")) {
            int leadingSpaces = countLeadingSpaces(content);
            if (leadingSpaces > 0 && leadingSpaces % indentSpaces != 0) {
                return new Violation(
                    Severity.INFO,
                    "INDENTATION",
                    String.format("Incorrect indentation. Expected multiple of %d spaces, found %d", indentSpaces, leadingSpaces),
                    lineNumber
                );
            }
        }
        
        // 라인 길이 체크
        if (content.length() > maxLineLength) {
            return new Violation(
                Severity.INFO,
                "LINE_LENGTH",
                String.format("Line exceeds maximum length of %d characters (found %d)", maxLineLength, content.length()),
                lineNumber
            );
        }
        
        // Brace 스타일 체크 (같은 라인 vs 다음 라인)
        if ("sameLine".equals(braceStyle) && content.trim().matches(".*\\{\\s*$")) {
            // sameLine 스타일에서는 { 가 같은 라인에 있어야 함 - 이미 만족
            return null;
        }
        
        return null;
    }
    
    /**
     * 선행 공백 수 카운트
     */
    private int countLeadingSpaces(String content) {
        int count = 0;
        for (char c : content.toCharArray()) {
            if (c == ' ') count++;
            else break;
        }
        return count;
    }

    /**
     * 공통 패턴 위반 체크
     * 
     * @param content 코드 내용
     * @param conventions 컨벤션 정보
     * @param lineNumber 라인번호
     * @return 위반 항목 (없으면 null)
     */
    private Violation checkCommonPatterns(String content, Conventions conventions, int lineNumber) {
        List<String> commonPatterns = conventions.getCommonPatterns();
        
        for (String pattern : commonPatterns) {
            // TODO: Implement pattern matching based on learned common patterns
            // 예: System.out.println 사용 금지, raw type 사용 금지 등
            if (pattern.contains("System.out.println") && content.contains("System.out.println")) {
                return new Violation(
                    Severity.WARNING,
                    "COMMON_PATTERN",
                    "Avoid using System.out.println in production code. Use logging framework instead.",
                    lineNumber
                );
            }
        }
        
        return null;
    }

    /**
     * JSON 파일에서 Diff 로드
     * 
     * @param diffPath JSON 파일 경로
     * @return 구조화된 Diff 객체
     * @throws IOException 파일 읽기 실패 시
     */
    public StructuredDiff loadDiff(Path diffPath) throws IOException {
        String json = Files.readString(diffPath);
        return objectMapper.readValue(json, StructuredDiff.class);
    }

    /**
     * JSON 파일에서 Conventions 로드
     * 
     * @param conventionsPath JSON 파일 경로
     * @return Conventions 객체
     * @throws IOException 파일 읽기 실패 시
     */
    public Conventions loadConventions(Path conventionsPath) throws IOException {
        String json = Files.readString(conventionsPath);
        return objectMapper.readValue(json, Conventions.class);
    }
}
