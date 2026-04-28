package com.reviewbot.features.convention.dto;

/**
 * Request body for convention learning from free-form text.
 */
public class LearnConventionsRequest {

    private String text;

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
}
