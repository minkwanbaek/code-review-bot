package com.reviewbot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * 파일별 리뷰 결과
 */
public class FileReview {
    private final String filePath;
    private final List<Violation> violations = new ArrayList<>();

    public FileReview(String filePath) {
        this.filePath = filePath;
    }

    public void addViolation(Violation violation) {
        this.violations.add(violation);
    }

    public String getFilePath() {
        return filePath;
    }

    public List<Violation> getViolations() {
        return violations;
    }

    public int getViolationCount() {
        return violations.size();
    }
}
