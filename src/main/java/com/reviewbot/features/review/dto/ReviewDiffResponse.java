package com.reviewbot.features.review.dto;

import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.Violation;

import java.util.List;

/**
 * Response payload for diff review results.
 */
public class ReviewDiffResponse {

    private final ReviewResult reviewResult;
    private final List<Violation> aiViolations;
    private final int totalViolations;
    private final boolean hasViolations;

    /**
     * Creates a diff review response.
     *
     * @param reviewResult deterministic review runner result
     * @param aiViolations optional AI violations returned by Ollama
     */
    public ReviewDiffResponse(ReviewResult reviewResult, List<Violation> aiViolations) {
        this.reviewResult = reviewResult;
        this.aiViolations = aiViolations == null ? List.of() : List.copyOf(aiViolations);
        this.totalViolations = reviewResult.getTotalViolations() + this.aiViolations.size();
        this.hasViolations = reviewResult.hasViolations() || !this.aiViolations.isEmpty();
    }

    /**
     * Returns deterministic review runner output.
     *
     * @return review result
     */
    public ReviewResult getReviewResult() {
        return reviewResult;
    }

    /**
     * Returns optional AI violations.
     *
     * @return AI violations
     */
    public List<Violation> getAiViolations() {
        return aiViolations;
    }

    /**
     * Returns total deterministic plus AI violations.
     *
     * @return total violation count
     */
    public int getTotalViolations() {
        return totalViolations;
    }

    /**
     * Returns whether any deterministic or AI violations were found.
     *
     * @return true when violations exist
     */
    public boolean isHasViolations() {
        return hasViolations;
    }
}
