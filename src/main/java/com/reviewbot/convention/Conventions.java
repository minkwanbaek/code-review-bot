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
    private List<ImportRule> importRules = new ArrayList<>();
    private Map<String, String> namingRules = new HashMap<>();
    private Map<String, Object> namingPatterns = new HashMap<>();
    private Map<String, Object> formattingRules = new HashMap<>();
    private List<String> commonPatterns = new ArrayList<>();
    private List<ForbiddenPattern> forbiddenPatterns = new ArrayList<>();
    private List<ArchRule> archRules = new ArrayList<>();
    private List<DependencyRule> dependencyRules = new ArrayList<>();

    /**
     * Import convention rule.
     */
    public static class ImportRule {
        private List<String> order = new ArrayList<>();
        private List<String> forbiddenImports = new ArrayList<>();
        private String message;

        /**
         * Creates an empty import rule for JSON binding.
         */
        public ImportRule() {
        }

        /**
         * Creates an import convention rule.
         *
         * @param order ordered import package groups
         * @param forbiddenImports import names or prefixes that are not allowed
         * @param message explanation for violations
         */
        public ImportRule(List<String> order, List<String> forbiddenImports, String message) {
            this.order = order;
            this.forbiddenImports = forbiddenImports;
            this.message = message;
        }

        /**
         * Returns ordered import package groups.
         *
         * @return import order
         */
        public List<String> getOrder() {
            return order;
        }

        /**
         * Sets ordered import package groups.
         *
         * @param order import order
         */
        public void setOrder(List<String> order) {
            this.order = order;
        }

        /**
         * Returns forbidden import names or prefixes.
         *
         * @return forbidden imports
         */
        public List<String> getForbiddenImports() {
            return forbiddenImports;
        }

        /**
         * Sets forbidden import names or prefixes.
         *
         * @param forbiddenImports forbidden imports
         */
        public void setForbiddenImports(List<String> forbiddenImports) {
            this.forbiddenImports = forbiddenImports;
        }

        /**
         * Returns the violation message.
         *
         * @return violation message
         */
        public String getMessage() {
            return message;
        }

        /**
         * Sets the violation message.
         *
         * @param message violation message
         */
        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * 금지 패턴 정보
     */
    public static class ForbiddenPattern {
        private String pattern;
        private String description;
        private String message;
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
            this.message = description;
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
            if (this.message == null || this.message.isBlank()) {
                this.message = description;
            }
        }

        /**
         * Returns the violation message.
         *
         * @return violation message
         */
        public String getMessage() {
            return message == null || message.isBlank() ? description : message;
        }

        /**
         * Sets the violation message.
         *
         * @param message violation message
         */
        public void setMessage(String message) {
            this.message = message;
            if (this.description == null || this.description.isBlank()) {
                this.description = message;
            }
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

    /**
     * Architecture dependency rule between layers.
     */
    public static class ArchRule {
        private String fromLayer;
        private String toLayer;
        private boolean allowed;
        private String severity;
        private String message;

        /**
         * Creates an empty architecture rule for JSON binding.
         */
        public ArchRule() {
        }

        /**
         * Creates an architecture dependency rule.
         *
         * @param fromLayer source layer
         * @param toLayer target layer
         * @param allowed whether this dependency is allowed
         * @param severity severity name
         * @param message violation message
         */
        public ArchRule(String fromLayer, String toLayer, boolean allowed, String severity, String message) {
            this.fromLayer = fromLayer;
            this.toLayer = toLayer;
            this.allowed = allowed;
            this.severity = severity;
            this.message = message;
        }

        /**
         * Returns the source layer.
         *
         * @return source layer
         */
        public String getFromLayer() {
            return fromLayer;
        }

        /**
         * Sets the source layer.
         *
         * @param fromLayer source layer
         */
        public void setFromLayer(String fromLayer) {
            this.fromLayer = fromLayer;
        }

        /**
         * Returns the target layer.
         *
         * @return target layer
         */
        public String getToLayer() {
            return toLayer;
        }

        /**
         * Sets the target layer.
         *
         * @param toLayer target layer
         */
        public void setToLayer(String toLayer) {
            this.toLayer = toLayer;
        }

        /**
         * Returns whether this dependency is allowed.
         *
         * @return true when allowed
         */
        public boolean isAllowed() {
            return allowed;
        }

        /**
         * Sets whether this dependency is allowed.
         *
         * @param allowed true when allowed
         */
        public void setAllowed(boolean allowed) {
            this.allowed = allowed;
        }

        /**
         * Returns the severity.
         *
         * @return severity name
         */
        public String getSeverity() {
            return severity;
        }

        /**
         * Sets the severity.
         *
         * @param severity severity name
         */
        public void setSeverity(String severity) {
            this.severity = severity;
        }

        /**
         * Returns the violation message.
         *
         * @return violation message
         */
        public String getMessage() {
            return message;
        }

        /**
         * Sets the violation message.
         *
         * @param message violation message
         */
        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * Layer-specific forbidden dependency rule.
     */
    public static class DependencyRule {
        private String layer;
        private List<String> forbiddenDependencies = new ArrayList<>();
        private String severity;
        private String message;

        /**
         * Creates an empty dependency rule for JSON binding.
         */
        public DependencyRule() {
        }

        /**
         * Creates a dependency rule.
         *
         * @param layer layer name
         * @param forbiddenDependencies forbidden dependencies for the layer
         * @param severity severity name
         * @param message violation message
         */
        public DependencyRule(String layer, List<String> forbiddenDependencies, String severity, String message) {
            this.layer = layer;
            this.forbiddenDependencies = forbiddenDependencies;
            this.severity = severity;
            this.message = message;
        }

        /**
         * Returns the layer name.
         *
         * @return layer name
         */
        public String getLayer() {
            return layer;
        }

        /**
         * Sets the layer name.
         *
         * @param layer layer name
         */
        public void setLayer(String layer) {
            this.layer = layer;
        }

        /**
         * Returns forbidden dependencies for this layer.
         *
         * @return forbidden dependencies
         */
        public List<String> getForbiddenDependencies() {
            return forbiddenDependencies;
        }

        /**
         * Sets forbidden dependencies for this layer.
         *
         * @param forbiddenDependencies forbidden dependencies
         */
        public void setForbiddenDependencies(List<String> forbiddenDependencies) {
            this.forbiddenDependencies = forbiddenDependencies;
        }

        /**
         * Returns the severity.
         *
         * @return severity name
         */
        public String getSeverity() {
            return severity;
        }

        /**
         * Sets the severity.
         *
         * @param severity severity name
         */
        public void setSeverity(String severity) {
            this.severity = severity;
        }

        /**
         * Returns the violation message.
         *
         * @return violation message
         */
        public String getMessage() {
            return message;
        }

        /**
         * Sets the violation message.
         *
         * @param message violation message
         */
        public void setMessage(String message) {
            this.message = message;
        }
    }

    public List<String> getImportOrder() {
        return importOrder;
    }

    public void setImportOrder(List<String> importOrder) {
        this.importOrder = importOrder;
    }

    /**
     * Returns structured import rules.
     *
     * @return import rules
     */
    public List<ImportRule> getImportRules() {
        return importRules;
    }

    /**
     * Sets structured import rules.
     *
     * @param importRules import rules
     */
    public void setImportRules(List<ImportRule> importRules) {
        this.importRules = importRules;
    }

    /**
     * Returns structured naming rules.
     *
     * @return naming rules
     */
    public Map<String, String> getNamingRules() {
        return namingRules;
    }

    /**
     * Sets structured naming rules.
     *
     * @param namingRules naming rules
     */
    public void setNamingRules(Map<String, String> namingRules) {
        this.namingRules = namingRules;
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
     * Returns architecture dependency rules.
     *
     * @return architecture rules
     */
    public List<ArchRule> getArchRules() {
        return archRules;
    }

    /**
     * Sets architecture dependency rules.
     *
     * @param archRules architecture rules
     */
    public void setArchRules(List<ArchRule> archRules) {
        this.archRules = archRules;
    }

    /**
     * Returns layer-specific dependency rules.
     *
     * @return dependency rules
     */
    public List<DependencyRule> getDependencyRules() {
        return dependencyRules;
    }

    /**
     * Sets layer-specific dependency rules.
     *
     * @param dependencyRules dependency rules
     */
    public void setDependencyRules(List<DependencyRule> dependencyRules) {
        this.dependencyRules = dependencyRules;
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
