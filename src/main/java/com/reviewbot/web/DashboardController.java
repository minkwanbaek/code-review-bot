package com.reviewbot.web;

import com.reviewbot.diff.Change;
import com.reviewbot.diff.ChangeType;
import com.reviewbot.diff.FileDiff;
import com.reviewbot.diff.Hunk;
import com.reviewbot.diff.StructuredDiff;
import com.reviewbot.review.FileReview;
import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
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

    // In-memory storage for settings (session-based)
    private final Map<String, Object> generalSettings = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> configuredRepos = new ArrayList<>();
    private final Map<Long, ReviewResult> reviewHistory = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> historyLog = new ArrayList<>();

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
        
        // 샘플 PR 데이터 (실제로는 서비스에서 가져옴)
        List<Map<String, Object>> recentPRs = getSamplePullRequests();
        model.addAttribute("recentPRs", recentPRs);
        
        // 리뷰 통계
        Map<String, Object> stats = getReviewStats();
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
        
        List<Map<String, Object>> prs = new ArrayList<>();
        
        if ("all".equals(provider) || "github".equals(provider)) {
            prs.addAll(getSamplePullRequests());
        }
        
        if ("all".equals(provider) || "bitbucket".equals(provider)) {
            prs.addAll(getSampleBitbucketPullRequests());
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
        
        // 샘플 리뷰 결과
        List<Map<String, Object>> reviews = getSampleReviews();
        
        if (prId != null && !prId.isEmpty()) {
            reviews.removeIf(r -> !prId.equals(String.valueOf(r.get("prId"))));
        }
        
        response.put("count", reviews.size());
        response.put("reviews", reviews);
        
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
     * 샘플 GitHub PR 데이터 생성
     */
    private List<Map<String, Object>> getSamplePullRequests() {
        List<Map<String, Object>> prs = new ArrayList<>();
        
        Map<String, Object> pr1 = new HashMap<>();
        pr1.put("id", 1);
        pr1.put("number", 42);
        pr1.put("title", "Feature: Add user authentication");
        pr1.put("author", "developer1");
        pr1.put("provider", "github");
        pr1.put("repo", "owner/project-a");
        pr1.put("state", "open");
        pr1.put("createdAt", LocalDateTime.now().minusHours(2).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr1.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr1.put("url", "https://github.com/owner/project-a/pull/42");
        prs.add(pr1);
        
        Map<String, Object> pr2 = new HashMap<>();
        pr2.put("id", 2);
        pr2.put("number", 15);
        pr2.put("title", "Fix: Resolve memory leak issue");
        pr2.put("author", "developer2");
        pr2.put("provider", "github");
        pr2.put("repo", "owner/project-b");
        pr2.put("state", "open");
        pr2.put("createdAt", LocalDateTime.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr2.put("updatedAt", LocalDateTime.now().minusHours(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr2.put("url", "https://github.com/owner/project-b/pull/15");
        prs.add(pr2);
        
        return prs;
    }

    /**
     * 샘플 Bitbucket PR 데이터 생성
     */
    private List<Map<String, Object>> getSampleBitbucketPullRequests() {
        List<Map<String, Object>> prs = new ArrayList<>();
        
        Map<String, Object> pr1 = new HashMap<>();
        pr1.put("id", 3);
        pr1.put("number", 8);
        pr1.put("title", "Refactor: Improve code structure");
        pr1.put("author", "dev3");
        pr1.put("provider", "bitbucket");
        pr1.put("repo", "workspace/project-c");
        pr1.put("state", "open");
        pr1.put("createdAt", LocalDateTime.now().minusHours(5).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr1.put("updatedAt", LocalDateTime.now().minusMinutes(30).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        pr1.put("url", "https://bitbucket.org/workspace/project-c/pull-requests/8");
        prs.add(pr1);
        
        return prs;
    }

    /**
     * 샘플 리뷰 데이터 생성
     */
    private List<Map<String, Object>> getSampleReviews() {
        List<Map<String, Object>> reviews = new ArrayList<>();
        
        Map<String, Object> review1 = new HashMap<>();
        review1.put("id", 1);
        review1.put("prId", 1);
        review1.put("prNumber", 42);
        review1.put("repo", "owner/project-a");
        review1.put("totalViolations", 5);
        review1.put("errorCount", 1);
        review1.put("warningCount", 3);
        review1.put("infoCount", 1);
        review1.put("status", "COMPLETED");
        review1.put("reviewedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        reviews.add(review1);
        
        Map<String, Object> review2 = new HashMap<>();
        review2.put("id", 2);
        review2.put("prId", 2);
        review2.put("prNumber", 15);
        review2.put("repo", "owner/project-b");
        review2.put("totalViolations", 0);
        review2.put("errorCount", 0);
        review2.put("warningCount", 0);
        review2.put("infoCount", 0);
        review2.put("status", "PASSED");
        review2.put("reviewedAt", LocalDateTime.now().minusHours(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        reviews.add(review2);
        
        return reviews;
    }

    /**
     * 리뷰 통계 생성
     */
    private Map<String, Object> getReviewStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalReviews", 127);
        stats.put("totalViolations", 342);
        stats.put("averageViolationsPerPR", 2.7);
        stats.put("passedPRs", 89);
        stats.put("failedPRs", 38);
        stats.put("passRate", "70.1%");
        return stats;
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
        // 샘플 PR 데이터 (실제로는 서비스에서 가져옴)
        Map<String, Object> pr = getSamplePullRequests().stream()
                .filter(p -> p.get("id").equals(id))
                .findFirst()
                .orElse(getSampleBitbucketPullRequests().stream()
                        .filter(p -> p.get("id").equals(id))
                        .findFirst()
                        .orElse(null));
        
        if (pr == null) {
            return "redirect:/";
        }
        
        model.addAttribute("pr", pr);
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
        
        // 샘플 Diff 데이터 생성
        StructuredDiff diff = createSampleDiff();
        response.put("diff", convertDiffToMap(diff));
        
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
        
        // 샘플 리뷰 결과
        ReviewResult review = createSampleReview();
        response.put("review", convertReviewToMap(review));
        
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
        
        // 샘플 히스토리 데이터 생성
        List<Map<String, Object>> allHistory = getSampleHistory();
        
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
        // 샘플 마크다운 리포트 생성
        String markdown = "# Code Review Report\n\n" +
                         "## PR #42\n\n" +
                         "### Summary\n" +
                         "- Total Violations: 5\n" +
                         "- Errors: 1\n" +
                         "- Warnings: 3\n" +
                         "- Info: 1\n\n" +
                         "### Details\n\n" +
                         "#### src/main/java/Example.java\n" +
                         "1. [ERROR] Line 42: Missing null check\n" +
                         "2. [WARNING] Line 15: Unused import\n" +
                         "3. [INFO] Line 28: Consider using StringBuilder\n";
        
        return markdown.getBytes();
    }

    /**
     * 샘플 히스토리 데이터 생성
     */
    private List<Map<String, Object>> getSampleHistory() {
        List<Map<String, Object>> history = new ArrayList<>();
        
        for (int i = 1; i <= 25; i++) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", (long) i);
            item.put("prId", (long) (i % 5 + 1));
            item.put("prNumber", 40 + i);
            item.put("repo", i % 3 == 0 ? "owner/project-a" : "owner/project-b");
            item.put("errorCount", i % 4);
            item.put("warningCount", i % 3);
            item.put("infoCount", i % 2);
            item.put("totalViolations", (i % 4) + (i % 3) + (i % 2));
            item.put("status", i % 5 == 0 ? "PASSED" : "COMPLETED");
            item.put("reviewedAt", LocalDateTime.now().minusDays(i).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            history.add(item);
        }
        
        return history;
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

    /**
     * 샘플 Diff 데이터 생성
     */
    private StructuredDiff createSampleDiff() {
        StructuredDiff diff = new StructuredDiff();
        
        FileDiff fileDiff = new FileDiff("src/main/java/Example.java", "src/main/java/Example.java");
        
        Hunk hunk1 = new Hunk(10, 10, "@@ -10,5 +10,7 @@");
        hunk1.addChange(new Change(ChangeType.CONTEXT, 10, 10, "public class Example {"));
        hunk1.addChange(new Change(ChangeType.CONTEXT, 11, 11, "    private String name;"));
        hunk1.addChange(new Change(ChangeType.ADDITION, 12, -1, "    private int age;"));
        hunk1.addChange(new Change(ChangeType.DELETION, 13, 12, "    // TODO: Add validation"));
        hunk1.addChange(new Change(ChangeType.CONTEXT, 14, 13, "}"));
        
        fileDiff.addHunk(hunk1);
        diff.addFile(fileDiff);
        
        return diff;
    }

    /**
     * 샘플 리뷰 결과 생성
     */
    private ReviewResult createSampleReview() {
        ReviewResult result = new ReviewResult();
        
        FileReview fileReview = new FileReview("src/main/java/Example.java");
        fileReview.addViolation(new Violation(Severity.ERROR, "NullCheck", "Missing null check for parameter", 15));
        fileReview.addViolation(new Violation(Severity.WARNING, "UnusedImport", "Unused import statement", 3));
        fileReview.addViolation(new Violation(Severity.WARNING, "CodeStyle", "Line too long (>120 characters)", 28));
        fileReview.addViolation(new Violation(Severity.INFO, "Performance", "Consider using StringBuilder for concatenation", 42));
        
        result.addFileReview(fileReview);
        
        return result;
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
