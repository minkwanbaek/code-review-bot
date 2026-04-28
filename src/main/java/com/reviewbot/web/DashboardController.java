package com.reviewbot.web;

import com.reviewbot.features.dashboard.service.ReviewHistoryComparisonService;

/**
 * Backward-compatible type alias for the feature-based dashboard controller.
 */
@Deprecated(forRemoval = false)
public class DashboardController extends com.reviewbot.features.dashboard.controller.DashboardController {

    /**
     * Creates the backward-compatible dashboard controller alias.
     *
     * @param comparisonService service used to compare review history entries
     */
    public DashboardController(ReviewHistoryComparisonService comparisonService) {
        super(comparisonService);
    }
}
