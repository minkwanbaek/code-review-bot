package com.reviewbot.review;

import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.FileDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks naming conventions for added Java declarations.
 */
public class NamingChecker {
    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+);");
    private static final Pattern TYPE_PATTERN = Pattern.compile("\\b(class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");
    private static final Pattern METHOD_PATTERN = Pattern.compile("\\b(?:public|private|protected)\\s+(?:static\\s+)?(?:final\\s+)?[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");
    private static final Pattern CONSTANT_PATTERN = Pattern.compile("\\b(?:public|private|protected)?\\s*(?:static\\s+final|final\\s+static)\\s+[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\b(?:public|private|protected)?\\s*(?:final\\s+)?[\\w<>\\[\\], ?]+\\s+([A-Za-z_$][A-Za-z0-9_$]*)\\s*=");

    /**
     * Checks one file diff for naming convention violations.
     *
     * @param file changed file
     * @param conventions structured conventions
     * @return detected violations
     */
    public List<Violation> check(FileDiff file, Conventions conventions) {
        List<Violation> violations = new ArrayList<>();
        if (!CheckerSupport.isJavaFile(file)) {
            return violations;
        }

        Map<String, String> rules = conventions.getNamingRules();
        Map<String, Object> legacyRules = conventions.getNamingPatterns();
        String classStyle = style(rules, legacyRules, "class", "PascalCase");
        String methodStyle = style(rules, legacyRules, "method", "camelCase");
        String variableStyle = style(rules, legacyRules, "variable", "camelCase");
        String constantStyle = style(rules, legacyRules, "constant", "UPPER_SNAKE_CASE");
        String packageStyle = style(rules, legacyRules, "package", "lowercase");

        for (CheckerSupport.AddedLine line : CheckerSupport.addedLines(file)) {
            String content = line.content();
            checkPackage(content, packageStyle, line.lineNumber(), violations);
            checkType(content, classStyle, line.lineNumber(), violations);
            boolean constantLine = checkConstant(content, constantStyle, line.lineNumber(), violations);
            checkMethod(content, methodStyle, line.lineNumber(), violations);
            if (!constantLine) {
                checkVariable(content, variableStyle, line.lineNumber(), violations);
            }
        }
        return violations;
    }

    private String style(Map<String, String> rules, Map<String, Object> legacyRules, String key, String fallback) {
        if (rules != null && rules.get(key) != null && !rules.get(key).isBlank()) {
            return rules.get(key);
        }
        if ("class".equals(key) && legacyRules != null && legacyRules.containsKey("namingStyle")) {
            Object style = legacyRules.get("namingStyle");
            if ("camelCase".equals(style)) {
                return "PascalCase";
            }
        }
        return fallback;
    }

    private void checkPackage(String content, String style, int lineNumber, List<Violation> violations) {
        Matcher matcher = PACKAGE_PATTERN.matcher(content);
        if (matcher.find() && "lowercase".equalsIgnoreCase(style) && !matcher.group(1).matches("[a-z0-9_.]+")) {
            violations.add(violation("PACKAGE_NAMING", "Package name '" + matcher.group(1) + "' should be lowercase", lineNumber));
        }
    }

    private void checkType(String content, String style, int lineNumber, List<Violation> violations) {
        Matcher matcher = TYPE_PATTERN.matcher(content);
        if (matcher.find() && !matchesStyle(matcher.group(2), style)) {
            violations.add(violation("NAMING_CONVENTION", "Type name '" + matcher.group(2) + "' should follow " + style, lineNumber));
        }
    }

    private void checkMethod(String content, String style, int lineNumber, List<Violation> violations) {
        Matcher matcher = METHOD_PATTERN.matcher(content);
        if (matcher.find() && !List.of("if", "for", "while", "switch", "catch").contains(matcher.group(1))
                && !matchesStyle(matcher.group(1), style)) {
            violations.add(violation("NAMING_CONVENTION", "Method name '" + matcher.group(1) + "' should follow " + style, lineNumber));
        }
    }

    private boolean checkConstant(String content, String style, int lineNumber, List<Violation> violations) {
        Matcher matcher = CONSTANT_PATTERN.matcher(content);
        if (!matcher.find()) {
            return false;
        }
        if (!matchesStyle(matcher.group(1), style)) {
            violations.add(violation("NAMING_CONVENTION", "Constant name '" + matcher.group(1) + "' should follow " + style, lineNumber));
        }
        return true;
    }

    private void checkVariable(String content, String style, int lineNumber, List<Violation> violations) {
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        if (matcher.find() && !content.trim().startsWith("return ") && !matchesStyle(matcher.group(1), style)) {
            violations.add(violation("NAMING_CONVENTION", "Variable name '" + matcher.group(1) + "' should follow " + style, lineNumber));
        }
    }

    private Violation violation(String rule, String message, int lineNumber) {
        return new Violation(Severity.WARNING, rule, message, lineNumber);
    }

    private boolean matchesStyle(String name, String style) {
        return switch (style) {
            case "PascalCase" -> name.matches("[A-Z][A-Za-z0-9]*");
            case "camelCase" -> name.matches("[a-z][A-Za-z0-9]*");
            case "UPPER_SNAKE_CASE" -> name.matches("[A-Z][A-Z0-9_]*");
            case "lowercase" -> name.matches("[a-z0-9_.]+");
            default -> true;
        };
    }
}
