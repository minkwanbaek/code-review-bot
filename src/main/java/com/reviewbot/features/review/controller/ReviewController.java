package com.reviewbot.features.review.controller;

import com.reviewbot.ai.OllamaClient;
import com.reviewbot.common.dto.ApiResponse;
import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.DiffAnalyzer;
import com.reviewbot.diff.StructuredDiff;
import com.reviewbot.features.review.dto.ReviewDiffRequest;
import com.reviewbot.features.review.dto.ReviewDiffResponse;
import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.ReviewRunner;
import com.reviewbot.review.Violation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * JSON endpoints for reviewing diffs.
 */
@RestController
public class ReviewController {

    private final DiffAnalyzer diffAnalyzer;
    private final ReviewRunner reviewRunner;
    private final OllamaClient ollamaClient;
    private final boolean aiEnabled;

    /**
     * Creates a review controller.
     *
     * @param ollamaClient Ollama client for optional AI review
     * @param aiEnabled whether AI review is enabled by default
     */
    public ReviewController(
            OllamaClient ollamaClient,
            @Value("${reviewbot.ai.enabled:false}") boolean aiEnabled) {
        this.diffAnalyzer = new DiffAnalyzer();
        this.reviewRunner = new ReviewRunner();
        this.ollamaClient = ollamaClient;
        this.aiEnabled = aiEnabled;
    }

    /**
     * Reviews raw diff text against supplied conventions.
     *
     * @param request diff review request
     * @return deterministic and optional AI review violations
     */
    @PostMapping("/api/review/diff")
    public ApiResponse<ReviewDiffResponse> reviewDiff(@RequestBody ReviewDiffRequest request) {
        String diffText = request == null ? "" : request.getDiffText();
        if (diffText == null || diffText.isBlank()) {
            throw new IllegalArgumentException("Diff text is required");
        }

        Conventions conventions = request.getConventions() == null ? new Conventions() : request.getConventions();
        StructuredDiff diff = diffAnalyzer.parseDiff(diffText);
        ReviewResult reviewResult = reviewRunner.review(diff, conventions);
        List<Violation> aiViolations = shouldRunAi(request)
                ? ollamaClient.reviewCode(diffText, conventions)
                : List.of();

        return ApiResponse.success(new ReviewDiffResponse(reviewResult, aiViolations));
    }

    private boolean shouldRunAi(ReviewDiffRequest request) {
        return request.getAiEnabled() == null ? aiEnabled : Boolean.TRUE.equals(request.getAiEnabled());
    }
}
