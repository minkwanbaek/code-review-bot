package com.reviewbot.web;

import com.reviewbot.features.dashboard.service.DashboardDataService;
import com.reviewbot.features.dashboard.service.ReviewHistoryComparisonService;

/**
 * Backward-compatible type alias for the feature-based dashboard controller.
 */
@Deprecated(forRemoval = false)
public class DashboardController extends com.reviewbot.features.dashboard.controller.DashboardController {

    /**
     * Creates the backward-compatible dashboard controller alias.
     *
     * @param dashboardDataService dashboard state persistence service
     * @param comparisonService service used to compare review history entries
     */
    public DashboardController(DashboardDataService dashboardDataService,
                               ReviewHistoryComparisonService comparisonService) {
        super(dashboardDataService, comparisonService);
    }
}
