package com.reviewbot.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewbot.features.dashboard.service.DashboardDataService;

/**
 * Backward-compatible type alias for the feature-based PR poll scheduler.
 */
@Deprecated(forRemoval = false)
public class PRPollScheduler extends com.reviewbot.features.polling.service.PRPollScheduler {

    public PRPollScheduler(DashboardDataService dashboardDataService, ObjectMapper objectMapper) {
        super(dashboardDataService, objectMapper);
    }
}
