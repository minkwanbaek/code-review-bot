package com.reviewbot.features.dashboard.controller;

import com.reviewbot.diff.Change;
import com.reviewbot.diff.ChangeType;
import com.reviewbot.diff.FileDiff;
import com.reviewbot.diff.Hunk;
import com.reviewbot.diff.StructuredDiff;
import com.reviewbot.review.FileReview;
import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
import com.reviewbot.features.dashboard.service.ReviewHistoryComparisonService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * US-5: Web 대시보드 컨트롤러
 * PR 목록 및 리뷰 결과 조회
 */
@Controller
@RequestMapping("/")
public class DashboardController {

    private static final Logger log = LoggerFactory.getLogger(DashboardController.class);

    @Value("${reviewbot.scheduler.poll-interval-ms:300000}")
    private long pollIntervalMs;

    @Value("${reviewbot.scheduler.enabled:true}")
    private boolean schedulerEnabled;

    // In-memory storage for settings (session-based)
    private final Map<String, Object> generalSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> configuredRepos = new ArrayList<>();
    private final Map<Long, ReviewResult> reviewHistory = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> historyLog = new ArrayList<>();
    private final List<Map<String, Object>> pullRequests = new ArrayList<>();
    private final Map<String, Map<String, Object>> batchJobs = new ConcurrentHashMap<>();
    private final AtomicLong historyIdSequence = new AtomicLong(3);
    private final ReviewHistoryComparisonService comparisonService;

    /**
     * Creates the dashboard controller.
     *
     * @param comparisonService service used to compare review history entries
     */
    public DashboardController(ReviewHistoryComparisonService comparisonService) {
        this.comparisonService = comparisonService;
        seedDashboardData();
    }

    /**
     * 메인 대시보드 페이지
     * 
     * @param model Spring MVC 모델
     * @return 템플릿 이름
     */
    @GetMapping
    public String index(Model model) {
        // 대시보드 데이터 설정
        model.addAttribute("title", "Code Review Bot Dashboard");
        model.addAttribute("currentTime", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        model.addAttribute("schedulerEnabled", schedulerEnabled);
        model.addAttribute("pollIntervalSeconds", pollIntervalMs / 1000);
        
        List<Map<String, Object>> recentPRs = getVisiblePullRequests();
        model.addAttribute("recentPRs", recentPRs);
        model.addAttribute("notification", buildReviewNotification(recentPRs));
        
        Map<String, Object> stats = getDashboardStats();
        model.addAttribute("stats", stats);
        
        return "index";
    }

    /**
     * PR 목록 API
     * 
     * @param provider 제공자 (github, bitbucket)
     * @param status PR 상태 (open, closed, all)
     * @param search 검색어 (타이틀/레포/작성자)
     * @return PR 목록 JSON
     */
    @GetMapping("/api/prs")
    @ResponseBody
    public Map<String, Object> getPullRequests(
            @RequestParam(value = "provider", required = false, defaultValue = "all") String provider,
            @RequestParam(value = "status", required = false, defaultValue = "open") String status,
            @RequestParam(value = "search", required = false, defaultValue = "") String search) {
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        List<Map<String, Object>> prs = new ArrayList<>(getVisiblePullRequests());
        
        // 상태 필터링
        if (!"all".equals(status)) {
            prs.removeIf(pr -> !status.equals(pr.get("state")));
        }
        
        // 검색 필터링
        if (search != null && !search.isEmpty()) {
            final String searchLower = search.toLowerCase();
            prs.removeIf(pr -> {
                String title = (String) pr.get("title");
                String repo = (String) pr.get("repo");
                String author = (String) pr.get("author");
                return !(title != null && title.toLowerCase().contains(searchLower)) &&
                       !(repo != null && repo.toLowerCase().contains(searchLower)) &&
                       !(author != null && author.toLowerCase().contains(searchLower));
            });
        }
        
        response.put("count", prs.size());
        response.put("pullRequests", prs);
        
        return response;
    }

    /**
     * 리뷰 결과 API
     * 
     * @param prId PR ID
     * @return 리뷰 결과 JSON
     */
    @GetMapping("/api/reviews")
    @ResponseBody
    public Map<String, Object> getReviewResults(@RequestParam(value = "prId", required = false) String prId) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        List<Map<String, Object>> reviews = new ArrayList<>(historyLog);
        
        if (prId != null && !prId.isEmpty()) {
            reviews.removeIf(r -> !prId.equals(String.valueOf(r.get("prId"))));
        }
        
        response.put("count", reviews.size());
        response.put("reviews", reviews);
        
        return response;
    }

    /**
     * Queues a batch review for selected pull requests.
     *
     * @param request JSON payload containing a {@code prIds} array
     * @return batch job status JSON
     */
    @PostMapping("/api/reviews/batch")
    @ResponseBody
    public Map<String, Object> startBatchReview(@RequestBody Map<String, Object> request) {
        List<Long> prIds = extractPrIds(request.get("prIds"));
        Map<String, Object> response = new HashMap<>();

        if (prIds.isEmpty()) {
            response.put("success", false);
            response.put("message", "Select at least one pull request");
            return response;
        }

        String batchId = UUID.randomUUID().toString();
        Map<String, Object> job = new ConcurrentHashMap<>();
        job.put("id", batchId);
        job.put("total", prIds.size());
        job.put("completed", 0);
        job.put("progress", 0);
        job.put("status", "RUNNING");
        job.put("message", "Batch review started");
        batchJobs.put(batchId, job);

        prIds.forEach(prId -> {
            runStubReview(prId);
            int completed = (Integer) job.get("completed") + 1;
            job.put("completed", completed);
            job.put("progress", completed * 100 / prIds.size());
        });

        job.put("status", "COMPLETED");
        job.put("message", "Batch review completed");
        response.put("success", true);
        response.put("batch", job);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        return response;
    }

    /**
     * Returns progress for a previously queued batch review.
     *
     * @param batchId batch job identifier
     * @return batch progress JSON
     */
    @GetMapping("/api/reviews/batch/{batchId}")
    @ResponseBody
    public Map<String, Object> getBatchReviewProgress(@PathVariable String batchId) {
        Map<String, Object> response = new HashMap<>();
        Map<String, Object> job = batchJobs.get(batchId);
        response.put("success", job != null);
        response.put("batch", job);
        if (job == null) {
            response.put("message", "Batch job not found");
        }
        return response;
    }

    /**
     * 시스템 상태 API
     * 
     * @return 시스템 상태 JSON
     */
    @GetMapping("/api/status")
    @ResponseBody
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("status", "UP");
        status.put("scheduler", schedulerEnabled ? "RUNNING" : "STOPPED");
        status.put("pollInterval", pollIntervalMs + "ms");
        status.put("uptime", getUptime());
        status.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        return status;
    }









    /**
     * 가동 시간 계산
     */
    private String getUptime() {
        long uptimeMillis = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long hours = uptimeMillis / 3600000;
        long minutes = (uptimeMillis % 3600000) / 60000;
        return String.format("%dh %dm", hours, minutes);
    }

    /**
     * PR 상세 페이지
     * 
     * @param id PR ID
     * @param model Spring MVC 모델
     * @return 템플릿 이름
     */
    @GetMapping("/pr/{id}")
    public String getPRDetail(@PathVariable Long id, Model model) {
        model.addAttribute("title", "Pull Request Detail");
        model.addAttribute("pr", findPullRequest(id).orElseGet(() -> createPlaceholderPullRequest(id)));
        return "pr-detail";
    }

    /**
     * PR Diff API
     * 
     * @param id PR ID
     * @return Diff JSON
     */
    @GetMapping("/api/pr/{id}/diff")
    @ResponseBody
    public Map<String, Object> getPRDiff(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        // 실제 API 연동 전까지 빈 diff 반환
        Map<String, Object> emptyDiff = new HashMap<>();
        emptyDiff.put("files", new ArrayList<>());
        response.put("diff", emptyDiff);
        
        return response;
    }

    /**
     * PR 리뷰 결과 API
     * 
     * @param id PR ID
     * @return 리뷰 결과 JSON
     */
    @GetMapping("/api/pr/{id}/review")
    @ResponseBody
    public Map<String, Object> getPRReview(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        // 실제 API 연동 전까지 빈 리뷰 결과 반환
        Map<String, Object> emptyReview = new HashMap<>();
        emptyReview.put("fileReviews", new ArrayList<>());
        emptyReview.put("totalViolations", 0);
        emptyReview.put("errorCount", 0);
        emptyReview.put("warningCount", 0);
        emptyReview.put("infoCount", 0);
        emptyReview.put("status", "PASSED");
        response.put("review", emptyReview);
        
        return response;
    }

    /**
     * 리뷰 재실행 API
     * 
     * @param id PR ID
     * @return 결과 JSON
     */
    @PostMapping("/api/pr/{id}/review")
    @ResponseBody
    public Map<String, Object> rerunReview(@PathVariable Long id) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Review has been queued for re-execution");
        response.put("timestamp", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        
        // 실제로는 여기서 리뷰를 다시 실행
        log.info("Re-running review for PR id={}", id);
        
        return response;
    }

    /**
     * 설정 페이지
     * 
     * @param model Spring MVC 모델
     * @return 템플릿 이름
     */
    @GetMapping("/settings")
    public String getSettings(Model model) {
        model.addAttribute("title", "Settings");
        return "settings";
    }

    /**
     * 저장된 레포지토리 목록 API
     * 
     * @return 레포지토리 목록 JSON
     */
    @GetMapping("/api/settings/repos")
    @ResponseBody
    public Map<String, Object> getConfiguredRepos() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("repos", configuredRepos);
        return response;
    }

    /**
     * 레포지토리 추가 API
     * 
     * @param repoData 레포지토리 데이터
     * @return 결과 JSON
     */
    @PostMapping("/api/settings/repos")
    @ResponseBody
    public Map<String, Object> addRepo(@RequestBody Map<String, Object> repoData) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            Map<String, Object> newRepo = new HashMap<>(repoData);
            newRepo.put("id", System.currentTimeMillis());
            newRepo.put("createdAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            
            if (!newRepo.containsKey("branches")) {
                newRepo.put("branches", Arrays.asList("main"));
            }
            
            configuredRepos.add(newRepo);
            
            response.put("success", true);
            response.put("message", "Repository added successfully");
            log.info("Added repository: {}", newRepo);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to add repository: " + e.getMessage());
            log.error("Failed to add repository", e);
        }
        
        return response;
    }

    /**
     * 레포지토리 삭제 API
     * 
     * @param index 레포지토리 인덱스
     * @return 결과 JSON
     */
    @DeleteMapping("/api/settings/repos/{index}")
    @ResponseBody
    public Map<String, Object> deleteRepo(@PathVariable int index) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            if (index >= 0 && index < configuredRepos.size()) {
                configuredRepos.remove(index);
                response.put("success", true);
                response.put("message", "Repository deleted successfully");
            } else {
                response.put("success", false);
                response.put("message", "Invalid repository index");
            }
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to delete repository: " + e.getMessage());
            log.error("Failed to delete repository", e);
        }
        
        return response;
    }

    /**
     * 연결 테스트 API
     * 
     * @param testData 테스트 데이터
     * @return 결과 JSON
     */
    @PostMapping("/api/settings/test-connection")
    @ResponseBody
    public Map<String, Object> testConnection(@RequestBody Map<String, Object> testData) {
        Map<String, Object> response = new HashMap<>();
        
        String provider = (String) testData.get("provider");
        log.info("Testing connection for provider: {}", provider);
        
        // Stub implementation - 실제 구현에서는 각 provider 의 API 를 호출
        response.put("success", true);
        response.put("message", "Connection test successful (stub)");
        
        return response;
    }

    /**
     * 일반 설정 저장 API
     * 
     * @param settings 설정 데이터
     * @return 결과 JSON
     */
    @PostMapping("/api/settings/general")
    @ResponseBody
    public Map<String, Object> saveGeneralSettings(@RequestBody Map<String, Object> settings) {
        Map<String, Object> response = new HashMap<>();
        
        try {
            generalSettings.putAll(settings);
            
            // Poll interval 업데이트
            if (settings.containsKey("pollInterval")) {
                this.pollIntervalMs = (Integer) settings.get("pollInterval");
            }
            
            response.put("success", true);
            response.put("message", "Settings saved successfully");
            log.info("Saved general settings: {}", settings);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to save settings: " + e.getMessage());
            log.error("Failed to save settings", e);
        }
        
        return response;
    }

    /**
     * 리뷰 히스토리 페이지
     * 
     * @param model Spring MVC 모델
     * @return 템플릿 이름
     */
    @GetMapping("/history")
    public String getHistory(Model model) {
        model.addAttribute("title", "Review History");
        return "history";
    }

    /**
     * 리뷰 히스토리 목록 API
     * 
     * @param startDate 시작일
     * @param endDate 종료일
     * @param repo 레포지토리 필터
     * @param status 상태 필터
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @return 히스토리 목록 JSON
     */
    @GetMapping("/api/history")
    @ResponseBody
    public Map<String, Object> getHistory(
            @RequestParam(value = "startDate", required = false) String startDate,
            @RequestParam(value = "endDate", required = false) String endDate,
            @RequestParam(value = "repo", required = false) String repo,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "10") int size) {
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        
        List<Map<String, Object>> allHistory = new ArrayList<>(historyLog);
        
        // 필터링
        if (repo != null && !repo.isEmpty()) {
            allHistory.removeIf(h -> !repo.equals(h.get("repo")));
        }
        if (status != null && !status.isEmpty() && !"ALL".equals(status)) {
            allHistory.removeIf(h -> !status.equals(h.get("status")));
        }
        
        // 페이지네이션
        int totalItems = allHistory.size();
        int totalPages = (int) Math.ceil((double) totalItems / size);
        int fromIndex = (page - 1) * size;
        int toIndex = Math.min(fromIndex + size, totalItems);
        
        List<Map<String, Object>> paginatedHistory = fromIndex < totalItems 
                ? allHistory.subList(fromIndex, toIndex) 
                : new ArrayList<>();
        
        response.put("history", paginatedHistory);
        response.put("total", totalItems);
        response.put("currentPage", page);
        response.put("totalPages", totalPages);
        
        // 통계
        response.put("stats", getHistoryStats(allHistory));
        
        // 레포지토리 목록
        Set<String> repos = new HashSet<>();
        allHistory.forEach(h -> {
            String r = (String) h.get("repo");
            if (r != null) repos.add(r);
        });
        response.put("repos", repos);
        
        return response;
    }

    /**
     * 리뷰 결과 다운로드 API
     * 
     * @param id 리뷰 ID
     * @return 마크다운 파일
     */
    @GetMapping("/api/history/{id}/download")
    @ResponseBody
    public byte[] downloadReviewReport(@PathVariable Long id) {
        // 실제 API 연동 전까지 404 반환
        log.warn("Download requested for review id={}, but no data available yet", id);
        return null; // Spring 이 404 처리
    }

    /**
     * Compares two review history entries and returns violation changes.
     *
     * @param baselineId older review history ID
     * @param targetId newer review history ID
     * @return JSON payload with new and resolved violations
     */
    @GetMapping("/api/history/compare")
    @ResponseBody
    public Map<String, Object> compareHistory(
            @RequestParam("baselineId") Long baselineId,
            @RequestParam("targetId") Long targetId) {
        Map<String, Object> response = new HashMap<>();
        Optional<Map<String, Object>> baseline = findHistoryEntry(baselineId);
        Optional<Map<String, Object>> target = findHistoryEntry(targetId);

        if (baseline.isEmpty() || target.isEmpty()) {
            response.put("success", false);
            response.put("message", "Review history entry not found");
            return response;
        }

        response.put("success", true);
        response.put("comparison", comparisonService.compare(baseline.get(), target.get()));
        return response;
    }



    /**
     * 히스토리 통계 생성
     */
    private Map<String, Object> getHistoryStats(List<Map<String, Object>> history) {
        Map<String, Object> stats = new HashMap<>();
        
        long totalReviews = history.size();
        long totalViolations = history.stream()
                .mapToLong(h -> (Integer) h.getOrDefault("totalViolations", 0))
                .sum();
        long passedPRs = history.stream()
                .filter(h -> "PASSED".equals(h.get("status")))
                .count();
        
        stats.put("totalReviews", totalReviews);
        stats.put("totalPRs", totalReviews);
        stats.put("averageViolations", totalReviews > 0 ? (double) totalViolations / totalReviews : 0);
        stats.put("passRate", totalReviews > 0 ? String.format("%.1f%%", (double) passedPRs / totalReviews * 100) : "0%");
        
        return stats;
    }

    private void seedDashboardData() {
        if (!pullRequests.isEmpty() || !historyLog.isEmpty()) {
            return;
        }

        pullRequests.add(newPullRequest(101L, 42, "Improve payment validation", "minkwan", "github",
                "reviewbot/core", "open", "2026-04-29T08:15:00", "https://github.com/example/reviewbot/pull/42",
                "PENDING"));
        pullRequests.add(newPullRequest(102L, 43, "Refactor dashboard templates", "ralph", "github",
                "reviewbot/core", "open", "2026-04-29T08:20:00", "https://github.com/example/reviewbot/pull/43",
                "PENDING"));

        historyLog.add(newHistoryEntry(1L, 101L, 42, "reviewbot/core", "FAILED",
                "2026-04-29T08:04:00",
                List.of(
                        newViolation("src/main/java/App.java", 18, "LINE_LENGTH", "Line exceeds maximum length", "INFO"),
                        newViolation("src/main/java/App.java", 22, "LOGGING", "Use logger instead of System.out", "WARNING"))));
        historyLog.add(newHistoryEntry(2L, 101L, 42, "reviewbot/core", "FAILED",
                "2026-04-29T08:18:00",
                List.of(
                        newViolation("src/main/java/App.java", 22, "LOGGING", "Use logger instead of System.out", "WARNING"),
                        newViolation("src/main/java/App.java", 31, "INDENTATION", "Incorrect indentation", "INFO"))));
    }

    private List<Map<String, Object>> getVisiblePullRequests() {
        return new ArrayList<>(pullRequests);
    }

    private Map<String, Object> buildReviewNotification(List<Map<String, Object>> prs) {
        long pendingCount = prs.stream()
                .filter(pr -> "PENDING".equals(pr.get("reviewStatus")))
                .count();
        long failedCount = historyLog.stream()
                .filter(review -> "FAILED".equals(review.get("status")))
                .count();

        Map<String, Object> notification = new HashMap<>();
        notification.put("pendingCount", pendingCount);
        notification.put("failedCount", failedCount);
        notification.put("message", pendingCount > 0
                ? pendingCount + " pull requests are waiting for review"
                : "All visible pull requests have been reviewed");
        notification.put("severity", failedCount > 0 ? "warning" : "info");
        return notification;
    }

    private Map<String, Object> getDashboardStats() {
        long totalReviews = historyLog.size();
        long totalViolations = historyLog.stream()
                .mapToLong(h -> (Integer) h.getOrDefault("totalViolations", 0))
                .sum();
        long passedPRs = historyLog.stream()
                .filter(h -> "PASSED".equals(h.get("status")))
                .count();
        long failedPRs = historyLog.stream()
                .filter(h -> "FAILED".equals(h.get("status")))
                .count();

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalReviews", totalReviews);
        stats.put("totalViolations", totalViolations);
        stats.put("averageViolationsPerPR", totalReviews > 0 ? totalViolations / totalReviews : 0);
        stats.put("passedPRs", passedPRs);
        stats.put("failedPRs", failedPRs);
        stats.put("passRate", totalReviews > 0 ? String.format("%.0f%%", (double) passedPRs / totalReviews * 100) : "0%");
        return stats;
    }

    private Map<String, Object> newPullRequest(Long id, int number, String title, String author, String provider,
                                               String repo, String state, String updatedAt, String url,
                                               String reviewStatus) {
        Map<String, Object> pr = new HashMap<>();
        pr.put("id", id);
        pr.put("number", number);
        pr.put("title", title);
        pr.put("author", author);
        pr.put("provider", provider);
        pr.put("repo", repo);
        pr.put("state", state);
        pr.put("updatedAt", updatedAt);
        pr.put("url", url);
        pr.put("reviewStatus", reviewStatus);
        return pr;
    }

    private Map<String, Object> newHistoryEntry(Long id, Long prId, int prNumber, String repo, String status,
                                                String reviewedAt, List<Map<String, Object>> violations) {
        long errorCount = violations.stream().filter(v -> "ERROR".equals(v.get("severity"))).count();
        long warningCount = violations.stream().filter(v -> "WARNING".equals(v.get("severity"))).count();
        long infoCount = violations.stream().filter(v -> "INFO".equals(v.get("severity"))).count();

        Map<String, Object> entry = new HashMap<>();
        entry.put("id", id);
        entry.put("prId", prId);
        entry.put("prNumber", prNumber);
        entry.put("repo", repo);
        entry.put("status", status);
        entry.put("reviewedAt", reviewedAt);
        entry.put("violations", violations);
        entry.put("totalViolations", violations.size());
        entry.put("errorCount", (int) errorCount);
        entry.put("warningCount", (int) warningCount);
        entry.put("infoCount", (int) infoCount);
        return entry;
    }

    private Map<String, Object> newViolation(String file, int lineNumber, String rule, String message, String severity) {
        Map<String, Object> violation = new HashMap<>();
        violation.put("file", file);
        violation.put("lineNumber", lineNumber);
        violation.put("rule", rule);
        violation.put("message", message);
        violation.put("severity", severity);
        return violation;
    }

    private List<Long> extractPrIds(Object rawPrIds) {
        if (!(rawPrIds instanceof List<?> values)) {
            return new ArrayList<>();
        }
        return values.stream()
                .map(value -> value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)))
                .toList();
    }

    private void runStubReview(Long prId) {
        pullRequests.stream()
                .filter(pr -> prId.equals(pr.get("id")))
                .findFirst()
                .ifPresent(pr -> {
                    pr.put("reviewStatus", "REVIEWED");
                    Long id = historyIdSequence.getAndIncrement();
                    List<Map<String, Object>> violations = List.of(
                            newViolation("src/main/java/Dashboard.java", 14, "LINE_LENGTH",
                                    "Line exceeds maximum length", "INFO"));
                    historyLog.add(newHistoryEntry(id, prId, (Integer) pr.get("number"), (String) pr.get("repo"),
                            "FAILED", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME), violations));
                });
    }

    private Optional<Map<String, Object>> findHistoryEntry(Long id) {
        return historyLog.stream()
                .filter(entry -> id.equals(entry.get("id")))
                .findFirst();
    }

    private Optional<Map<String, Object>> findPullRequest(Long id) {
        return pullRequests.stream()
                .filter(pr -> id.equals(pr.get("id")))
                .findFirst();
    }

    private Map<String, Object> createPlaceholderPullRequest(Long id) {
        Map<String, Object> pr = new HashMap<>();
        pr.put("id", id);
        pr.put("number", id);
        pr.put("title", "Pull request #" + id);
        pr.put("author", "unknown");
        pr.put("repo", "unknown/repository");
        pr.put("provider", "github");
        pr.put("state", "open");
        pr.put("url", "#");
        log.warn("PR detail requested for id={}, using placeholder data", id);
        return pr;
    }





    /**
     * Diff 를 Map 으로 변환
     */
    private Map<String, Object> convertDiffToMap(StructuredDiff diff) {
        Map<String, Object> map = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        
        for (FileDiff file : diff.getFiles()) {
            Map<String, Object> fileMap = new HashMap<>();
            fileMap.put("oldPath", file.getOldPath());
            fileMap.put("newPath", file.getNewPath());
            
            List<Map<String, Object>> hunks = new ArrayList<>();
            for (Hunk hunk : file.getHunks()) {
                Map<String, Object> hunkMap = new HashMap<>();
                hunkMap.put("lineNumberOld", hunk.getLineNumberOld());
                hunkMap.put("lineNumberNew", hunk.getLineNumberNew());
                hunkMap.put("context", hunk.getContext());
                
                List<Map<String, Object>> changes = new ArrayList<>();
                for (Change change : hunk.getChanges()) {
                    Map<String, Object> changeMap = new HashMap<>();
                    changeMap.put("type", change.getType().name());
                    changeMap.put("lineNumberNew", change.getLineNumberNew());
                    changeMap.put("lineNumberOld", change.getLineNumberOld());
                    changeMap.put("content", change.getContent());
                    changes.add(changeMap);
                }
                hunkMap.put("changes", changes);
                hunks.add(hunkMap);
            }
            fileMap.put("hunks", hunks);
            files.add(fileMap);
        }
        
        map.put("files", files);
        return map;
    }

    /**
     * ReviewResult 를 Map 으로 변환
     */
    private Map<String, Object> convertReviewToMap(ReviewResult result) {
        Map<String, Object> map = new HashMap<>();
        
        int errorCount = 0, warningCount = 0, infoCount = 0;
        List<Map<String, Object>> fileReviews = new ArrayList<>();
        
        for (FileReview fileReview : result.getFileReviews()) {
            Map<String, Object> fileMap = new HashMap<>();
            fileMap.put("filePath", fileReview.getFilePath());
            
            List<Map<String, Object>> violations = new ArrayList<>();
            for (Violation v : fileReview.getViolations()) {
                Map<String, Object> vMap = new HashMap<>();
                vMap.put("severity", v.getSeverity().name());
                vMap.put("rule", v.getRule());
                vMap.put("message", v.getMessage());
                vMap.put("lineNumber", v.getLineNumber());
                
                if (v.getSeverity() == Severity.ERROR) errorCount++;
                else if (v.getSeverity() == Severity.WARNING) warningCount++;
                else infoCount++;
                
                violations.add(vMap);
            }
            fileMap.put("violations", violations);
            fileReviews.add(fileMap);
        }
        
        map.put("fileReviews", fileReviews);
        map.put("totalViolations", result.getTotalViolations());
        map.put("errorCount", errorCount);
        map.put("warningCount", warningCount);
        map.put("infoCount", infoCount);
        map.put("status", errorCount > 0 ? "FAILED" : "PASSED");
        
        return map;
    }
}
