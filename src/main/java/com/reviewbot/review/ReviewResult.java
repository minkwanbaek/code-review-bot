package com.reviewbot.review;

import java.util.ArrayList;
import java.util.List;

/**
 * 리뷰 결과
 */
public class ReviewResult {
    private final List<FileReview> fileReviews = new ArrayList<>();

    public void addFileReview(FileReview fileReview) {
        this.fileReviews.add(fileReview);
    }

    public List<FileReview> getFileReviews() {
        return fileReviews;
    }

    public int getTotalViolations() {
        return fileReviews.stream()
                .mapToInt(f -> f.getViolations().size())
                .sum();
    }

    public boolean hasViolations() {
        return !fileReviews.isEmpty();
    }
}
