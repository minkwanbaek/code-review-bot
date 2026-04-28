package com.reviewbot.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
     * @return PR 목록 JSON
     */
    @GetMapping("/api/prs")
    @ResponseBody
    public Map<String, Object> getPullRequests(
            @RequestParam(value = "provider", required = false, defaultValue = "all") String provider,
            @RequestParam(value = "status", required = false, defaultValue = "open") String status) {
        
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
}
