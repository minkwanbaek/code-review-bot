package com.reviewbot.features.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardDataServiceTest {

    @TempDir
    Path dataDir;

    @Test
    void storesReposPullRequestsSettingsAndHistoryOnDisk() {
        DashboardDataService service = new DashboardDataService(new ObjectMapper(), dataDir);

        Map<String, Object> repo = service.addRepo(Map.of(
                "provider", "github",
                "owner", "openai",
                "repo", "reviewbot"));
        Map<String, Object> pr = service.upsertPullRequest(Map.of(
                "provider", "github",
                "repo", "openai/reviewbot",
                "number", 7,
                "title", "Make dashboard real",
                "author", "minkwan",
                "state", "open"));
        service.saveGeneralSettings(Map.of("pollInterval", 60000));
        service.addHistoryEntry(Map.of(
                "eventKey", "github/openai/reviewbot/main/abc123",
                "eventType", "COMMIT",
                "repo", "openai/reviewbot",
                "branch", "main",
                "commitSha", "abc123"));

        DashboardDataService reloaded = new DashboardDataService(new ObjectMapper(), dataDir);

        assertThat(reloaded.getConfiguredRepos()).extracting(item -> String.valueOf(item.get("id"))).contains(String.valueOf(repo.get("id")));
        assertThat(reloaded.getPullRequests()).extracting(item -> String.valueOf(item.get("id"))).contains(String.valueOf(pr.get("id")));
        assertThat(((Number) reloaded.getGeneralSettings().get("pollInterval")).longValue()).isEqualTo(60000L);
        assertThat(reloaded.hasHistoryEvent("github/openai/reviewbot/main/abc123")).isTrue();
        assertThat(reloaded.getHistory()).hasSize(1);
        assertThat((List<Map<String, Object>>) reloaded.getHistory().getFirst().get("violations")).isEmpty();
    }
}
