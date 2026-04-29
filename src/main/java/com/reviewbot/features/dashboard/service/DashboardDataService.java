package com.reviewbot.features.dashboard.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Service
public class DashboardDataService {

    private static final Logger log = LoggerFactory.getLogger(DashboardDataService.class);
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Path settingsFile;
    private final Path reposFile;
    private final Path pullRequestsFile;
    private final Path historyFile;
    private final AtomicLong repoIdSequence = new AtomicLong(1);
    private final AtomicLong prIdSequence = new AtomicLong(1);
    private final AtomicLong historyIdSequence = new AtomicLong(1);

    private Map<String, Object> generalSettings;
    private List<Map<String, Object>> configuredRepos;
    private List<Map<String, Object>> pullRequests;
    private List<Map<String, Object>> historyLog;

    public DashboardDataService(ObjectMapper objectMapper,
                                @Value("${reviewbot.dashboard.data-dir:.reviewbot/dashboard}") Path dataDir) {
        this.objectMapper = objectMapper.findAndRegisterModules();
        this.dataDir = dataDir;
        this.settingsFile = dataDir.resolve("settings.json");
        this.reposFile = dataDir.resolve("repos.json");
        this.pullRequestsFile = dataDir.resolve("pull-requests.json");
        this.historyFile = dataDir.resolve("history.json");
        load();
    }

    public synchronized Map<String, Object> getGeneralSettings() {
        return new HashMap<>(generalSettings);
    }

    public synchronized void saveGeneralSettings(Map<String, Object> settings) {
        generalSettings.putAll(settings);
        write(settingsFile, generalSettings);
    }

    public synchronized List<Map<String, Object>> getConfiguredRepos() {
        return copyList(configuredRepos);
    }

    public synchronized Map<String, Object> addRepo(Map<String, Object> repoData) {
        Map<String, Object> repo = new LinkedHashMap<>(repoData);
        repo.putIfAbsent("id", repoIdSequence.getAndIncrement());
        repo.putIfAbsent("createdAt", now());
        repo.putIfAbsent("branches", List.of("main"));
        normalizeProvider(repo);
        configuredRepos.add(repo);
        configuredRepos.sort(Comparator.comparing(repoEntry -> String.valueOf(repoEntry.get("provider"))));
        write(reposFile, configuredRepos);
        return new HashMap<>(repo);
    }

    public synchronized boolean deleteRepo(int index) {
        if (index < 0 || index >= configuredRepos.size()) {
            return false;
        }
        configuredRepos.remove(index);
        write(reposFile, configuredRepos);
        return true;
    }

    public synchronized List<Map<String, Object>> getPullRequests() {
        return copyList(pullRequests);
    }

    public synchronized Optional<Map<String, Object>> findPullRequest(Long id) {
        return pullRequests.stream()
                .filter(pr -> id.equals(asLong(pr.get("id"))))
                .findFirst()
                .<Map<String, Object>>map(HashMap::new);
    }

    public synchronized Map<String, Object> upsertPullRequest(Map<String, Object> prData) {
        Map<String, Object> normalized = new LinkedHashMap<>(prData);
        normalizeProvider(normalized);
        normalized.putIfAbsent("id", stablePrId(normalized));
        normalized.putIfAbsent("reviewStatus", "PENDING");
        normalized.putIfAbsent("updatedAt", now());
        normalized.putIfAbsent("url", "");
        normalized.putIfAbsent("author", "unknown");
        normalized.putIfAbsent("title", "Untitled change");
        normalized.putIfAbsent("state", "open");

        Long id = asLong(normalized.get("id"));
        Optional<Map<String, Object>> existing = pullRequests.stream()
                .filter(pr -> id.equals(asLong(pr.get("id"))))
                .findFirst();

        if (existing.isPresent()) {
            existing.get().putAll(normalized);
            write(pullRequestsFile, pullRequests);
            return new HashMap<>(existing.get());
        }

        pullRequests.add(normalized);
        pullRequests.sort(Comparator.comparing(pr -> String.valueOf(pr.getOrDefault("updatedAt", "")), Comparator.reverseOrder()));
        write(pullRequestsFile, pullRequests);
        prIdSequence.updateAndGet(current -> Math.max(current, id + 1));
        return new HashMap<>(normalized);
    }

    public synchronized boolean markPullRequestReviewed(Long prId) {
        Optional<Map<String, Object>> existing = pullRequests.stream()
                .filter(pr -> prId.equals(asLong(pr.get("id"))))
                .findFirst();
        existing.ifPresent(pr -> pr.put("reviewStatus", "REVIEWED"));
        if (existing.isPresent()) {
            write(pullRequestsFile, pullRequests);
        }
        return existing.isPresent();
    }

    public synchronized List<Map<String, Object>> getHistory() {
        return copyList(historyLog);
    }

    public synchronized Optional<Map<String, Object>> findHistoryEntry(Long id) {
        return historyLog.stream()
                .filter(entry -> id.equals(asLong(entry.get("id"))))
                .findFirst()
                .<Map<String, Object>>map(HashMap::new);
    }

    public synchronized Map<String, Object> addHistoryEntry(Map<String, Object> entryData) {
        Map<String, Object> entry = new LinkedHashMap<>(entryData);
        entry.putIfAbsent("id", historyIdSequence.getAndIncrement());
        entry.putIfAbsent("reviewedAt", now());
        entry.putIfAbsent("eventType", "REVIEW");
        entry.putIfAbsent("violations", List.of());
        List<?> violations = entry.get("violations") instanceof List<?> values ? values : List.of();
        entry.putIfAbsent("totalViolations", violations.size());
        entry.putIfAbsent("errorCount", countSeverity(violations, "ERROR"));
        entry.putIfAbsent("warningCount", countSeverity(violations, "WARNING"));
        entry.putIfAbsent("infoCount", countSeverity(violations, "INFO"));
        entry.putIfAbsent("status", "COMPLETED");
        historyLog.add(entry);
        historyLog.sort(Comparator.comparing(history -> String.valueOf(history.getOrDefault("reviewedAt", "")), Comparator.reverseOrder()));
        write(historyFile, historyLog);
        historyIdSequence.updateAndGet(current -> Math.max(current, asLong(entry.get("id")) + 1));
        return new HashMap<>(entry);
    }

    public synchronized boolean hasHistoryEvent(String eventKey) {
        if (eventKey == null || eventKey.isBlank()) {
            return false;
        }
        return historyLog.stream().anyMatch(entry -> eventKey.equals(entry.get("eventKey")));
    }

    public synchronized Set<String> getHistoryRepos() {
        Set<String> repos = new HashSet<>();
        historyLog.forEach(entry -> {
            Object repo = entry.get("repo");
            if (repo != null && !String.valueOf(repo).isBlank()) {
                repos.add(String.valueOf(repo));
            }
        });
        return repos;
    }

    private void load() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create dashboard data dir: " + dataDir, e);
        }

        generalSettings = read(settingsFile, MAP, new HashMap<>());
        configuredRepos = read(reposFile, LIST_OF_MAPS, new ArrayList<>());
        pullRequests = read(pullRequestsFile, LIST_OF_MAPS, new ArrayList<>());
        historyLog = read(historyFile, LIST_OF_MAPS, new ArrayList<>());
        syncSequences();
    }

    private void syncSequences() {
        repoIdSequence.set(nextId(configuredRepos));
        prIdSequence.set(nextId(pullRequests));
        historyIdSequence.set(nextId(historyLog));
    }

    private long nextId(List<Map<String, Object>> entries) {
        return entries.stream()
                .map(entry -> asLong(entry.get("id")))
                .filter(id -> id != null)
                .mapToLong(Long::longValue)
                .max()
                .orElse(0L) + 1;
    }

    private long countSeverity(List<?> violations, String severity) {
        return violations.stream()
                .filter(violation -> violation instanceof Map<?, ?> map && severity.equals(map.get("severity")))
                .count();
    }

    private <T> T read(Path file, TypeReference<T> type, T fallback) {
        if (!Files.exists(file)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(file.toFile(), type);
        } catch (IOException e) {
            log.warn("Failed to read dashboard data file {}: {}", file, e.getMessage());
            return fallback;
        }
    }

    private void write(Path file, Object value) {
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write dashboard data file: " + file, e);
        }
    }

    private List<Map<String, Object>> copyList(List<Map<String, Object>> source) {
        return source.stream()
                .<Map<String, Object>>map(HashMap::new)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private Long stablePrId(Map<String, Object> pr) {
        Object provider = pr.getOrDefault("provider", "");
        Object repo = pr.getOrDefault("repo", "");
        Object number = pr.getOrDefault("number", prIdSequence.getAndIncrement());
        return (long) Math.abs((provider + ":" + repo + ":" + number).hashCode());
    }

    private void normalizeProvider(Map<String, Object> entry) {
        if ("bitbucket-cloud".equals(entry.get("provider"))) {
            entry.put("provider", "bitbucket");
        }
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }
}
