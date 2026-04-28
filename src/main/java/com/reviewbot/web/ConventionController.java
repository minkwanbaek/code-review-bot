package com.reviewbot.web;

import com.reviewbot.features.convention.service.ConventionService;

/**
 * Backward-compatible type alias for the feature-based convention controller.
 */
@Deprecated(forRemoval = false)
public class ConventionController extends com.reviewbot.features.convention.controller.ConventionController {

    /**
     * Creates a compatibility convention controller.
     *
     * @param conventionService service for convention persistence and learning
     */
    public ConventionController(ConventionService conventionService) {
        super(conventionService);
    }
}
