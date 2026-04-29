package com.reviewbot.review;

import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.FileDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks architecture layer dependency rules against added imports.
 */
public class LayerChecker {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+);");

    /**
     * Checks one file diff for layer dependency violations.
     *
     * @param file changed file
     * @param conventions structured conventions
     * @return detected violations
     */
    public List<Violation> check(FileDiff file, Conventions conventions) {
        List<Violation> violations = new ArrayList<>();
        if (!CheckerSupport.isJavaFile(file) || conventions.getArchRules() == null || conventions.getArchRules().isEmpty()) {
            return violations;
        }

        String fromLayer = detectLayer(file.getNewPath());
        if (fromLayer == null) {
            return violations;
        }

        for (CheckerSupport.AddedLine line : CheckerSupport.addedLines(file)) {
            Matcher matcher = IMPORT_PATTERN.matcher(line.content());
            if (!matcher.find()) {
                continue;
            }
            String imported = matcher.group(1);
            String toLayer = detectLayer(imported);
            if (toLayer == null) {
                continue;
            }

            for (Conventions.ArchRule rule : conventions.getArchRules()) {
                if (!rule.isAllowed()
                        && sameLayer(rule.getFromLayer(), fromLayer)
                        && sameLayer(rule.getToLayer(), toLayer)) {
                    violations.add(new Violation(
                            CheckerSupport.severity(rule.getSeverity(), Severity.ERROR),
                            "LAYER_DEPENDENCY",
                            message(rule, fromLayer, toLayer, imported),
                            line.lineNumber()
                    ));
                }
            }
        }
        return violations;
    }

    private String message(Conventions.ArchRule rule, String fromLayer, String toLayer, String imported) {
        if (rule.getMessage() != null && !rule.getMessage().isBlank()) {
            return rule.getMessage();
        }
        return "Layer '" + fromLayer + "' must not depend on layer '" + toLayer + "' via " + imported;
    }

    private boolean sameLayer(String expected, String actual) {
        return expected != null && actual != null && expected.equalsIgnoreCase(actual);
    }

    private String detectLayer(String value) {
        String normalized = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        String dotted = normalized.replace('/', '.');
        for (String layer : List.of("controller", "service", "repository", "domain", "dto", "config")) {
            if (dotted.contains("." + layer + ".")
                    || dotted.contains("/" + layer + "/")
                    || dotted.endsWith(layer)
                    || dotted.endsWith(layer + ".java")) {
                return layer;
            }
            String suffix = layer.equals("repository") ? "repository" : layer;
            if (dotted.matches(".*[a-z0-9]" + suffix + "(\\.java)?$")) {
                return layer;
            }
        }
        return null;
    }
}
