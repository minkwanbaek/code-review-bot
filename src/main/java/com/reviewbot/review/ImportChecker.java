package com.reviewbot.review;

import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.FileDiff;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks import convention rules against added Java imports.
 */
public class ImportChecker {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.*]+);");

    /**
     * Checks one file diff for forbidden imports and import order violations.
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

        List<ImportLine> imports = importLines(file);
        if (imports.isEmpty()) {
            return violations;
        }

        List<Conventions.ImportRule> rules = conventions.getImportRules() == null
                ? List.of()
                : conventions.getImportRules();
        for (Conventions.ImportRule rule : rules) {
            checkForbiddenImports(imports, rule, violations);
            checkImportOrder(imports, rule.getOrder(), rule.getMessage(), violations);
        }

        if (rules.isEmpty() && conventions.getImportOrder() != null && !conventions.getImportOrder().isEmpty()) {
            checkImportOrder(imports, conventions.getImportOrder(), "Imports are not in configured order", violations);
        }

        return violations;
    }

    private void checkForbiddenImports(List<ImportLine> imports, Conventions.ImportRule rule, List<Violation> violations) {
        if (rule.getForbiddenImports() == null) {
            return;
        }
        for (ImportLine importLine : imports) {
            for (String forbidden : rule.getForbiddenImports()) {
                if (matchesForbidden(importLine.name(), forbidden)) {
                    violations.add(new Violation(
                            Severity.ERROR,
                            "FORBIDDEN_IMPORT",
                            rule.getMessage() == null || rule.getMessage().isBlank()
                                    ? "Forbidden import: " + importLine.name()
                                    : rule.getMessage(),
                            importLine.lineNumber()
                    ));
                }
            }
        }
    }

    private void checkImportOrder(List<ImportLine> imports, List<String> order, String message, List<Violation> violations) {
        if (order == null || order.isEmpty()) {
            return;
        }
        int previousRank = -1;
        for (ImportLine importLine : imports) {
            int rank = rank(importLine.name(), order);
            if (rank < 0) {
                continue;
            }
            if (rank < previousRank) {
                violations.add(new Violation(
                        Severity.WARNING,
                        "IMPORT_ORDER",
                        message == null || message.isBlank() ? "Import order does not match configured order" : message,
                        importLine.lineNumber()
                ));
                return;
            }
            previousRank = rank;
        }
    }

    private List<ImportLine> importLines(FileDiff file) {
        List<ImportLine> imports = new ArrayList<>();
        for (CheckerSupport.AddedLine line : CheckerSupport.addedLines(file)) {
            Matcher matcher = IMPORT_PATTERN.matcher(line.content());
            if (matcher.find()) {
                imports.add(new ImportLine(line.lineNumber(), matcher.group(1)));
            }
        }
        return imports;
    }

    private boolean matchesForbidden(String importName, String forbidden) {
        if (forbidden == null || forbidden.isBlank()) {
            return false;
        }
        String normalized = forbidden.trim();
        if (normalized.endsWith(".*")) {
            String prefix = normalized.substring(0, normalized.length() - 1);
            return importName.startsWith(prefix);
        }
        return importName.equals(normalized) || importName.startsWith(normalized + ".");
    }

    private int rank(String importName, List<String> order) {
        for (int i = 0; i < order.size(); i++) {
            String prefix = order.get(i);
            if (prefix != null && importName.startsWith(prefix.endsWith(".") ? prefix : prefix + ".")) {
                return i;
            }
        }
        return -1;
    }

    private record ImportLine(int lineNumber, String name) {
    }
}
