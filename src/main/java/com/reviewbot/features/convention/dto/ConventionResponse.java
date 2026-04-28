package com.reviewbot.features.convention.dto;

import com.reviewbot.convention.Conventions;

/**
 * Convention API payload.
 */
public class ConventionResponse {

    private final Conventions conventions;

    /**
     * Creates a convention response payload.
     *
     * @param conventions conventions returned by the API
     */
    public ConventionResponse(Conventions conventions) {
        this.conventions = conventions;
    }

    /**
     * Returns conventions.
     *
     * @return conventions
     */
    public Conventions getConventions() {
        return conventions;
    }
}
