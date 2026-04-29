package com.reviewbot.features.review.dto;

import com.reviewbot.convention.Conventions;

/**
 * Request body for reviewing raw diff text against supplied conventions.
 */
public class ReviewDiffRequest {

    private String diffText;
    private Conventions conventions;
    private Boolean aiEnabled;

    /**
     * Returns raw unified diff text.
     *
     * @return raw diff text
     */
    public String getDiffText() {
        return diffText;
    }

    /**
     * Sets raw unified diff text.
     *
     * @param diffText raw diff text
     */
    public void setDiffText(String diffText) {
        this.diffText = diffText;
    }

    /**
     * Returns conventions used by the review runner.
     *
     * @return conventions for this review
     */
    public Conventions getConventions() {
        return conventions;
    }

    /**
     * Sets conventions used by the review runner.
     *
     * @param conventions conventions for this review
     */
    public void setConventions(Conventions conventions) {
        this.conventions = conventions;
    }

    /**
     * Returns whether Ollama AI review should run for this request.
     *
     * @return true when AI review is requested
     */
    public Boolean getAiEnabled() {
        return aiEnabled;
    }

    /**
     * Sets whether Ollama AI review should run for this request.
     *
     * @param aiEnabled true when AI review is requested
     */
    public void setAiEnabled(Boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }
}
