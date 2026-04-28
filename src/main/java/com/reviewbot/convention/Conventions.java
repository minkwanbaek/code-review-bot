package com.reviewbot.convention;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 코드 컨벤션 정보
 */
public class Conventions {
    private List<String> importOrder = new ArrayList<>();
    private Map<String, Object> namingPatterns = new HashMap<>();
    private Map<String, Object> formattingRules = new HashMap<>();
    private List<String> commonPatterns = new ArrayList<>();
    private List<ForbiddenPattern> forbiddenPatterns = new ArrayList<>();

    /**
     * 금지 패턴 정보
     */
    public static class ForbiddenPattern {
        private String pattern;
        private String description;
        private String severity;

        /**
         * Creates an empty forbidden pattern for JSON binding.
         */
        public ForbiddenPattern() {
        }

        /**
         * Creates a forbidden pattern rule.
         *
         * @param pattern text or regex pattern to flag
         * @param description explanation shown in review results
         * @param severity severity name
         */
        public ForbiddenPattern(String pattern, String description, String severity) {
            this.pattern = pattern;
            this.description = description;
            this.severity = severity;
        }

        /**
         * Returns the forbidden text or regex pattern.
         *
         * @return forbidden pattern
         */
        public String getPattern() {
            return pattern;
        }

        /**
         * Sets the forbidden text or regex pattern.
         *
         * @param pattern forbidden pattern
         */
        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        /**
         * Returns the rule description.
         *
         * @return rule description
         */
        public String getDescription() {
            return description;
        }

        /**
         * Sets the rule description.
         *
         * @param description rule description
         */
        public void setDescription(String description) {
            this.description = description;
        }

        /**
         * Returns the rule severity.
         *
         * @return severity name
         */
        public String getSeverity() {
            return severity;
        }

        /**
         * Sets the rule severity.
         *
         * @param severity severity name
         */
        public void setSeverity(String severity) {
            this.severity = severity;
        }
    }

    public List<String> getImportOrder() {
        return importOrder;
    }

    public void setImportOrder(List<String> importOrder) {
        this.importOrder = importOrder;
    }

    public Map<String, Object> getNamingPatterns() {
        return namingPatterns;
    }

    public void setNamingPatterns(Map<String, Object> namingPatterns) {
        this.namingPatterns = namingPatterns;
    }

    public Map<String, Object> getFormattingRules() {
        return formattingRules;
    }

    public void setFormattingRules(Map<String, Object> formattingRules) {
        this.formattingRules = formattingRules;
    }

    public List<String> getCommonPatterns() {
        return commonPatterns;
    }

    public void setCommonPatterns(List<String> commonPatterns) {
        this.commonPatterns = commonPatterns;
    }

    public List<ForbiddenPattern> getForbiddenPatterns() {
        return forbiddenPatterns;
    }

    /**
     * Replaces the forbidden pattern rules.
     *
     * @param forbiddenPatterns forbidden pattern rules
     */
    public void setForbiddenPatterns(List<ForbiddenPattern> forbiddenPatterns) {
        this.forbiddenPatterns = forbiddenPatterns;
    }

    /**
     * Adds one forbidden pattern rule.
     *
     * @param pattern forbidden pattern rule
     */
    public void addForbiddenPattern(ForbiddenPattern pattern) {
        if (this.forbiddenPatterns == null) {
            this.forbiddenPatterns = new ArrayList<>();
        }
        this.forbiddenPatterns.add(pattern);
    }
}
