package com.reviewbot.features.convention.dto;

/**
 * Request body for convention learning from free-form text.
 */
public class LearnConventionsRequest {

    private String text;
    private Boolean aiEnabled;

    /**
     * Returns the convention text to analyze.
     *
     * @return convention text
     */
    public String getText() {
        return text;
    }

    /**
     * Sets the convention text to analyze.
     *
     * @param text convention text
     */
    public void setText(String text) {
        this.text = text;
    }

    /**
     * Returns whether AI learning should be used for this request.
     *
     * @return true when AI learning is requested
     */
    public Boolean getAiEnabled() {
        return aiEnabled;
    }

    /**
     * Sets whether AI learning should be used for this request.
     *
     * @param aiEnabled true when AI learning is requested
     */
    public void setAiEnabled(Boolean aiEnabled) {
        this.aiEnabled = aiEnabled;
    }
}
