package com.reviewbot.features.dashboard.controller;

import com.reviewbot.features.dashboard.service.DashboardDataService;
import com.reviewbot.features.dashboard.service.ReviewHistoryComparisonService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

    private final Map<String, Map<String, Object>> batchJobs = new ConcurrentHashMap<>();
    private final DashboardDataService dashboardDataService;
    private final ReviewHistoryComparisonService comparisonService;

    /**
     * Creates the dashboard controller.
     *
     * @param comparisonService service used to compare review history entries
     */
    public DashboardController(DashboardDataService dashboardDataService,
                               ReviewHistoryComparisonService comparisonService) {
        this.dashboardDataService = dashboardDataService;
        this.comparisonService = comparisonService;
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

        if (!"all".equals(provider)) {
            prs.removeIf(pr -> !provider.equals(pr.get("provider")));
        }
        
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
        
        List<Map<String, Object>> reviews = dashboardDataService.getHistory();
        
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
            dashboardDataService.markPullRequestReviewed(prId);
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
        Optional<Map<String, Object>> pr = dashboardDataService.findPullRequest(id);
        model.addAttribute("pr", pr.orElseGet(() -> createPlaceholderPullRequest(id)));
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
        
        dashboardDataService.getHistory().stream()
                .filter(entry -> id.equals(asLong(entry.get("prId"))))
                .filter(entry -> entry.get("diff") instanceof Map<?, ?>)
                .findFirst()
                .ifPresentOrElse(entry -> response.put("diff", entry.get("diff")), () -> {
                    Map<String, Object> emptyDiff = new HashMap<>();
                    emptyDiff.put("files", new ArrayList<>());
                    response.put("diff", emptyDiff);
                });
        
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
        
        dashboardDataService.getHistory().stream()
                .filter(entry -> id.equals(asLong(entry.get("prId"))))
                .findFirst()
                .ifPresentOrElse(entry -> response.put("review", entry), () -> {
                    Map<String, Object> emptyReview = new HashMap<>();
                    emptyReview.put("fileReviews", new ArrayList<>());
                    emptyReview.put("totalViolations", 0);
                    emptyReview.put("errorCount", 0);
                    emptyReview.put("warningCount", 0);
                    emptyReview.put("infoCount", 0);
                    emptyReview.put("status", "NOT_REVIEWED");
                    response.put("review", emptyReview);
                });
        
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
        
        boolean knownPr = dashboardDataService.markPullRequestReviewed(id);
        response.put("success", knownPr);
        response.put("message", knownPr
                ? "Review has been marked for scheduler re-processing when provider data changes"
                : "Pull request not found");
        log.info("Re-running review requested for PR id={} known={}", id, knownPr);
        
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
        response.put("repos", dashboardDataService.getConfiguredRepos());
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
            Map<String, Object> newRepo = dashboardDataService.addRepo(repoData);
            
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
            if (dashboardDataService.deleteRepo(index)) {
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
        
        boolean configured = switch (provider == null ? "" : provider) {
            case "github" -> !String.valueOf(testData.getOrDefault("token", "")).isBlank()
                    || !String.valueOf(System.getenv().getOrDefault("GITHUB_TOKEN", "")).isBlank();
            case "bitbucket", "bitbucket-cloud" -> !String.valueOf(testData.getOrDefault("username", "")).isBlank()
                    && !String.valueOf(testData.getOrDefault("appPassword", "")).isBlank()
                    || (!String.valueOf(System.getenv().getOrDefault("BITBUCKET_USERNAME", "")).isBlank()
                    && !String.valueOf(System.getenv().getOrDefault("BITBUCKET_APP_PASSWORD", "")).isBlank());
            case "bitbucket-server" -> !String.valueOf(testData.getOrDefault("token", "")).isBlank()
                    || !String.valueOf(System.getenv().getOrDefault("BITBUCKET_SERVER_TOKEN", "")).isBlank();
            default -> false;
        };
        response.put("success", configured);
        response.put("message", configured
                ? "Connection settings are present. Live provider check runs during polling."
                : "Missing credentials for " + provider);
        
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
            dashboardDataService.saveGeneralSettings(settings);
            
            // Poll interval 업데이트
            Object pollInterval = settings.get("pollInterval");
            if (pollInterval instanceof Number number) {
                this.pollIntervalMs = number.longValue();
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

    @GetMapping("/api/settings/general")
    @ResponseBody
    public Map<String, Object> getGeneralSettings() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("settings", dashboardDataService.getGeneralSettings());
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
        
        List<Map<String, Object>> allHistory = dashboardDataService.getHistory();
        
        // 필터링
        if (repo != null && !repo.isEmpty()) {
            allHistory.removeIf(h -> !repo.equals(h.get("repo")));
        }
        if (status != null && !status.isEmpty() && !"ALL".equals(status)) {
            allHistory.removeIf(h -> !status.equals(h.get("status")));
        }
        if (startDate != null && !startDate.isBlank()) {
            allHistory.removeIf(h -> String.valueOf(h.getOrDefault("reviewedAt", "")).compareTo(startDate) < 0);
        }
        if (endDate != null && !endDate.isBlank()) {
            allHistory.removeIf(h -> String.valueOf(h.getOrDefault("reviewedAt", "")).compareTo(endDate + "T23:59:59") > 0);
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
    public ResponseEntity<byte[]> downloadReviewReport(@PathVariable Long id) {
        Optional<Map<String, Object>> entry = dashboardDataService.findHistoryEntry(id);
        if (entry.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        byte[] report = buildMarkdownReport(entry.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/markdown"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"review-" + id + ".md\"")
                .body(report);
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
        Optional<Map<String, Object>> baseline = dashboardDataService.findHistoryEntry(baselineId);
        Optional<Map<String, Object>> target = dashboardDataService.findHistoryEntry(targetId);

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
        List<Map<String, Object>> reviews = history.stream()
                .filter(entry -> !"COMMIT".equals(entry.get("eventType")))
                .toList();

        long totalReviews = reviews.size();
        long totalViolations = reviews.stream()
                .mapToLong(h -> longValueOrZero(h.get("totalViolations")))
                .sum();
        long passedPRs = reviews.stream()
                .filter(h -> "PASSED".equals(h.get("status")))
                .count();
        
        stats.put("totalReviews", totalReviews);
        stats.put("totalPRs", reviews.stream().map(h -> h.get("prId")).filter(Objects::nonNull).distinct().count());
        stats.put("totalEvents", history.size());
        stats.put("averageViolations", totalReviews > 0 ? (double) totalViolations / totalReviews : 0);
        stats.put("passRate", totalReviews > 0 ? String.format("%.1f%%", (double) passedPRs / totalReviews * 100) : "0%");
        
        return stats;
    }

    private List<Map<String, Object>> getVisiblePullRequests() {
        return dashboardDataService.getPullRequests();
    }

    private Map<String, Object> buildReviewNotification(List<Map<String, Object>> prs) {
        long pendingCount = prs.stream()
                .filter(pr -> "PENDING".equals(pr.get("reviewStatus")))
                .count();
        long failedCount = dashboardDataService.getHistory().stream()
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
        List<Map<String, Object>> history = dashboardDataService.getHistory();
        List<Map<String, Object>> reviews = history.stream()
                .filter(entry -> !"COMMIT".equals(entry.get("eventType")))
                .toList();
        long totalReviews = reviews.size();
        long totalViolations = reviews.stream()
                .mapToLong(h -> longValueOrZero(h.get("totalViolations")))
                .sum();
        long passedPRs = reviews.stream()
                .filter(h -> "PASSED".equals(h.get("status")))
                .count();
        long failedPRs = reviews.stream()
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

    private List<Long> extractPrIds(Object rawPrIds) {
        if (!(rawPrIds instanceof List<?> values)) {
            return new ArrayList<>();
        }
        return values.stream()
                .map(value -> value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value)))
                .toList();
    }

    private Map<String, Object> createPlaceholderPullRequest(Long id) {
        Map<String, Object> placeholder = new HashMap<>();
        placeholder.put("id", id);
        placeholder.put("number", id);
        placeholder.put("title", "Pull request not loaded yet");
        placeholder.put("author", "unknown");
        placeholder.put("repo", "untracked/repository");
        placeholder.put("provider", "github");
        placeholder.put("state", "open");
        placeholder.put("reviewStatus", "PENDING");
        placeholder.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        placeholder.put("isPlaceholder", true);
        return placeholder;
    }

    private String buildMarkdownReport(Map<String, Object> entry) {
        StringBuilder report = new StringBuilder();
        report.append("# Review History ").append(entry.getOrDefault("id", "")).append("\n\n");
        report.append("- Event: ").append(entry.getOrDefault("eventType", "REVIEW")).append("\n");
        report.append("- Repository: ").append(entry.getOrDefault("repo", "-")).append("\n");
        report.append("- PR: #").append(entry.getOrDefault("prNumber", "-")).append("\n");
        report.append("- Status: ").append(entry.getOrDefault("status", "-")).append("\n");
        report.append("- Reviewed At: ").append(entry.getOrDefault("reviewedAt", "-")).append("\n");
        report.append("- Total Violations: ").append(entry.getOrDefault("totalViolations", 0)).append("\n\n");

        Object violationsObject = entry.get("violations");
        if (violationsObject instanceof List<?> violations && !violations.isEmpty()) {
            report.append("## Violations\n\n");
            for (Object violationObject : violations) {
                if (violationObject instanceof Map<?, ?> violation) {
                    report.append("- ")
                            .append(valueOrDefault(violation, "severity", "INFO"))
                            .append(" ")
                            .append(valueOrDefault(violation, "rule", "-"))
                            .append(": ")
                            .append(valueOrDefault(violation, "message", "-"))
                            .append(" (")
                            .append(valueOrDefault(violation, "file", "-"))
                            .append(":")
                            .append(valueOrDefault(violation, "lineNumber", "-"))
                            .append(")\n");
                }
            }
        }
        return report.toString();
    }

    private Object valueOrDefault(Map<?, ?> map, String key, Object fallback) {
        Object value = map.get(key);
        return value == null ? fallback : value;
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

    private long longValueOrZero(Object value) {
        Long parsed = asLong(value);
        return parsed == null ? 0L : parsed;
    }
}
