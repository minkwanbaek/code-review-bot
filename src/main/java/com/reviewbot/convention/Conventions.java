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
}
