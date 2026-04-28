package com.reviewbot.features.dashboard.dto;

/**
 * Dashboard status payload.
 *
 * @param status application status
 * @param scheduler scheduler state
 * @param pollInterval configured poll interval
 * @param uptime process uptime
 * @param timestamp response timestamp
 */
public record DashboardStatusResponse(
        String status,
        String scheduler,
        String pollInterval,
        String uptime,
        String timestamp) {
}
