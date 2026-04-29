package com.reviewbot.features.polling.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.reviewbot.diff.Change;
import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.FileDiff;
import com.reviewbot.diff.Hunk;
import com.reviewbot.diff.StructuredDiff;
import com.reviewbot.features.dashboard.service.DashboardDataService;
import com.reviewbot.report.Reporter;
import com.reviewbot.review.FileReview;
import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.ReviewRunner;
import com.reviewbot.review.Severity;
import com.reviewbot.review.Violation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * US-6: PR poll scheduler
 * 설정된 interval 로 repo 의 open PR 을 polling, 새 PR/업데이트된 PR 자동 리뷰 실행
 */
@Component
@ConditionalOnProperty(name = "reviewbot.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PRPollScheduler {

    private static final Logger log = LoggerFactory.getLogger(PRPollScheduler.class);

    @Value("${reviewbot.scheduler.poll-interval-ms:300000}")
    private long pollIntervalMs;

    @Value("${reviewbot.github.token:}")
    private String githubToken;

    @Value("${reviewbot.bitbucket.username:}")
    private String bitbucketUsername;

    @Value("${reviewbot.bitbucket.app-password:}")
    private String bitbucketAppPassword;

    @Value("${reviewbot.bitbucket-server.token:}")
    private String bitbucketServerToken;

    @Value("${reviewbot.output.default-lang:ko}")
    private String defaultLang;

    @Value("${reviewbot.output.default-format:terminal}")
    private String defaultFormat;

    @Value("${reviewbot.output.file-output-dir:./reports}")
    private String fileOutputDir;

    private final ReviewRunner reviewRunner = new ReviewRunner();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final DashboardDataService dashboardDataService;
    private final ObjectMapper objectMapper;
    
    // 마지막으로 확인한 PR 업데이트 시간 추적 (중복 리뷰 방지)
    private final Map<String, LocalDateTime> lastCheckedPRs = new ConcurrentHashMap<>();

    public PRPollScheduler(DashboardDataService dashboardDataService, ObjectMapper objectMapper) {
        this.dashboardDataService = dashboardDataService;
        this.objectMapper = objectMapper.findAndRegisterModules();
    }

    /**
     * 주기적으로 PR polling 실행
     * Spring @Scheduled 를 사용하여 설정된 인터벌로 자동 실행
     */
    @Scheduled(fixedRateString = "${reviewbot.scheduler.poll-interval-ms:300000}")
    public void pollPullRequests() {
        log.info("Starting PR polling at {}", LocalDateTime.now());
        
        try {
            // GitHub PRs 확인
            pollGitHubPullRequests();

            // GitHub branch commit changes 확인
            pollGitHubBranchCommits();
            
            // Bitbucket Cloud PRs 확인
            pollBitbucketPullRequests();
            
            // Bitbucket Server PRs 확인
            pollBitbucketServerPullRequests();
            
            log.info("PR polling completed successfully");
        } catch (Exception e) {
            log.error("Error during PR polling: {}", e.getMessage(), e);
        }
    }

    /**
     * GitHub Pull Requests 폴링
     */
    private void pollGitHubPullRequests() {
        log.debug("Polling GitHub pull requests...");
        
        List<Map<String, String>> githubRepos = getGitHubReposFromConfig();
        
        for (Map<String, String> repo : githubRepos) {
            String owner = repo.get("owner");
            String repoName = repo.get("repo");
            
            if (owner == null || repoName == null) {
                continue;
            }
            
            try {
                List<Map<String, Object>> openPRs = fetchGitHubPullRequests(owner, repoName);
                
                for (Map<String, Object> pr : openPRs) {
                    processGitHubPR(owner, repoName, pr);
                }
            } catch (Exception e) {
                log.error("Error polling GitHub repo {}/{}: {}", owner, repoName, e.getMessage());
            }
        }
    }

    private void pollGitHubBranchCommits() {
        log.debug("Polling GitHub branch commits...");

        for (Map<String, String> repo : getGitHubReposFromConfig()) {
            String owner = repo.get("owner");
            String repoName = repo.get("repo");
            if (owner == null || repoName == null) {
                continue;
            }

            List<String> branches = branchesFrom(repo);
            for (String branch : branches) {
                try {
                    Map<String, Object> commit = fetchGitHubBranchHead(owner, repoName, branch);
                    recordGitHubBranchHead(owner, repoName, branch, commit);
                } catch (Exception e) {
                    log.error("Error polling GitHub branch {}/{} {}: {}", owner, repoName, branch, e.getMessage());
                }
            }
        }
    }

    /**
     * Bitbucket Pull Requests 폴링
     */
    private void pollBitbucketPullRequests() {
        log.debug("Polling Bitbucket Cloud pull requests...");
        
        // 설정에서 Bitbucket repos 가져오기
        List<Map<String, String>> bitbucketRepos = getBitbucketReposFromConfig();
        
        for (Map<String, String> repo : bitbucketRepos) {
            String workspace = repo.get("workspace");
            String repoName = repo.get("repo");
            
            if (workspace == null || repoName == null) {
                continue;
            }
            
            try {
                List<Map<String, Object>> openPRs = fetchBitbucketPullRequests(workspace, repoName);
                
                for (Map<String, Object> pr : openPRs) {
                    processBitbucketPR(workspace, repoName, pr);
                }
            } catch (Exception e) {
                log.error("Error polling Bitbucket repo {}/{}: {}", workspace, repoName, e.getMessage());
            }
        }
    }

    /**
     * Bitbucket Server Pull Requests 폴링
     * 온프레미스 Bitbucket Server 인스턴스에서 PR 을 조회
     */
    private void pollBitbucketServerPullRequests() {
        log.debug("Polling Bitbucket Server pull requests...");
        
        // 설정에서 Bitbucket Server repos 가져오기
        List<Map<String, String>> bitbucketServerRepos = getBitbucketServerReposFromConfig();
        
        for (Map<String, String> repo : bitbucketServerRepos) {
            String host = repo.get("host");
            String project = repo.get("project");
            String repoName = repo.get("repo");
            
            if (host == null || project == null || repoName == null) {
                continue;
            }
            
            try {
                List<Map<String, Object>> openPRs = fetchBitbucketServerPullRequests(host, project, repoName);
                
                for (Map<String, Object> pr : openPRs) {
                    processBitbucketServerPR(host, project, repoName, pr);
                }
            } catch (Exception e) {
                log.error("Error polling Bitbucket Server repo {}/{}/{}: {}", host, project, repoName, e.getMessage());
            }
        }
    }

    /**
     * GitHub PR 처리
     */
    private void processGitHubPR(String owner, String repo, Map<String, Object> pr) {
        int prNumber = numberAsInt(pr.get("number"));
        String prKey = String.format("github/%s/%s/%d", owner, repo, prNumber);
        String updatedAt = (String) pr.get("updated_at");
        Long prId = upsertGitHubPullRequest(owner, repo, pr);
        
        LocalDateTime prUpdatedAt = parseDateTime(updatedAt);
        LocalDateTime lastChecked = lastCheckedPRs.get(prKey);
        String eventKey = prKey + "/" + updatedAt;
        
        // 이미 확인한 PR 이고 업데이트되지 않았으면 스킵
        if ((lastChecked != null && !prUpdatedAt.isAfter(lastChecked)) || dashboardDataService.hasHistoryEvent(eventKey)) {
            log.debug("PR {} already reviewed, skipping", prKey);
            lastCheckedPRs.put(prKey, prUpdatedAt);
            return;
        }
        
        log.info("Processing GitHub PR #{} from {}/{}", prNumber, owner, repo);
        
        try {
            // Diff 가져오기
            StructuredDiff diff = fetchGitHubPRDiff(owner, repo, prNumber);
            
            // Conventions 로드
            Conventions conventions = loadConventions();
            
            // 리뷰 실행
            ReviewResult result = reviewRunner.review(diff, conventions);
            
            // 결과 리포트
            reportResults(prKey, (String) pr.get("html_url"), result);
            dashboardDataService.addHistoryEntry(reviewHistoryEntry(
                    eventKey,
                    prId,
                    prNumber,
                    owner + "/" + repo,
                    (String) pr.get("html_url"),
                    result,
                    diff));
            dashboardDataService.markPullRequestReviewed(prId);
            
            // 마지막 확인 시간 업데이트
            lastCheckedPRs.put(prKey, prUpdatedAt);
            
            log.info("Completed review for GitHub PR #{} - {} violations found", prNumber, result.getTotalViolations());
        } catch (Exception e) {
            log.error("Error processing GitHub PR #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * Bitbucket PR 처리
     */
    private void processBitbucketPR(String workspace, String repo, Map<String, Object> pr) {
        int prNumber = numberAsInt(pr.get("id"));
        String prKey = String.format("bitbucket/%s/%s/%d", workspace, repo, prNumber);
        String updatedAt = (String) pr.get("updated_on");
        Long prId = upsertBitbucketPullRequest(workspace, repo, pr);
        
        LocalDateTime prUpdatedAt = parseDateTime(updatedAt);
        LocalDateTime lastChecked = lastCheckedPRs.get(prKey);
        String eventKey = prKey + "/" + updatedAt;
        
        // 이미 확인한 PR 이고 업데이트되지 않았으면 스킵
        if ((lastChecked != null && !prUpdatedAt.isAfter(lastChecked)) || dashboardDataService.hasHistoryEvent(eventKey)) {
            log.debug("PR {} already reviewed, skipping", prKey);
            lastCheckedPRs.put(prKey, prUpdatedAt);
            return;
        }
        
        log.info("Processing Bitbucket Cloud PR #{} from {}/{}", prNumber, workspace, repo);
        
        try {
            // Diff 가져오기
            StructuredDiff diff = fetchBitbucketPRDiff(workspace, repo, prNumber);
            
            // Conventions 로드
            Conventions conventions = loadConventions();
            
            // 리뷰 실행
            ReviewResult result = reviewRunner.review(diff, conventions);
            
            // 결과 리포트
            @SuppressWarnings("unchecked")
            Map<String, Object> links = (Map<String, Object>) pr.get("links");
            String prUrl = null;
            if (links != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> html = (Map<String, Object>) links.get("html");
                if (html != null) {
                    prUrl = (String) html.get("href");
                }
            }
            reportResults(prKey, prUrl, result);
            dashboardDataService.addHistoryEntry(reviewHistoryEntry(
                    eventKey,
                    prId,
                    prNumber,
                    workspace + "/" + repo,
                    prUrl,
                    result,
                    diff));
            dashboardDataService.markPullRequestReviewed(prId);
            
            // 마지막 확인 시간 업데이트
            lastCheckedPRs.put(prKey, prUpdatedAt);
            
            log.info("Completed review for Bitbucket Cloud PR #{} - {} violations found", prNumber, result.getTotalViolations());
        } catch (Exception e) {
            log.error("Error processing Bitbucket Cloud PR #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * Bitbucket Server PR 처리
     * 
     * @param host Bitbucket Server 호스트 URL
     * @param project 프로젝트 키
     * @param repo 저장소 이름
     * @param pr PR 정보
     */
    private void processBitbucketServerPR(String host, String project, String repo, Map<String, Object> pr) {
        int prNumber = numberAsInt(pr.get("id"));
        String prKey = String.format("bitbucket-server/%s/%s/%s/%d", host, project, repo, prNumber);
        Long prId = upsertBitbucketServerPullRequest(host, project, repo, pr);
        
        // Bitbucket Server 는 updatedTime 필드 사용 (timestamp)
        Object updatedTimeObj = pr.get("updatedTime");
        LocalDateTime prUpdatedAt = LocalDateTime.now();
        if (updatedTimeObj instanceof Number number) {
            prUpdatedAt = LocalDateTime.ofEpochSecond(number.longValue() / 1000, 0, java.time.ZoneOffset.UTC);
        }
        
        LocalDateTime lastChecked = lastCheckedPRs.get(prKey);
        String eventKey = prKey + "/" + (updatedTimeObj == null ? "unknown" : updatedTimeObj);
        
        // 이미 확인한 PR 이고 업데이트되지 않았으면 스킵
        if ((lastChecked != null && !prUpdatedAt.isAfter(lastChecked)) || dashboardDataService.hasHistoryEvent(eventKey)) {
            log.debug("PR {} already reviewed, skipping", prKey);
            lastCheckedPRs.put(prKey, prUpdatedAt);
            return;
        }
        
        log.info("Processing Bitbucket Server PR #{} from {}/{}/{}", prNumber, host, project, repo);
        
        try {
            // Diff 가져오기
            StructuredDiff diff = fetchBitbucketServerPRDiff(host, project, repo, prNumber);
            
            // Conventions 로드
            Conventions conventions = loadConventions();
            
            // 리뷰 실행
            ReviewResult result = reviewRunner.review(diff, conventions);
            
            // 결과 리포트
            String prUrl = String.format("%s/projects/%s/repos/%s/pull-requests/%d", host, project, repo, prNumber);
            reportResults(prKey, prUrl, result);
            dashboardDataService.addHistoryEntry(reviewHistoryEntry(
                    eventKey,
                    prId,
                    prNumber,
                    project + "/" + repo,
                    prUrl,
                    result,
                    diff));
            dashboardDataService.markPullRequestReviewed(prId);
            
            // 마지막 확인 시간 업데이트
            lastCheckedPRs.put(prKey, prUpdatedAt);
            
            log.info("Completed review for Bitbucket Server PR #{} - {} violations found", prNumber, result.getTotalViolations());
        } catch (Exception e) {
            log.error("Error processing Bitbucket Server PR #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * GitHub Pull Requests 가져오기
     */
    private List<Map<String, Object>> fetchGitHubPullRequests(String owner, String repo) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/%s/pulls?state=open&per_page=100", owner, repo);
        
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
                .GET();
        if (githubToken != null && !githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }
        HttpRequest request = builder.build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode());
        }
        
        return parseJsonArray(response.body());
    }

    private Map<String, Object> fetchGitHubBranchHead(String owner, String repo, String branch) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/%s/commits/%s", owner, repo, branch);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3+json")
                .GET();
        if (githubToken != null && !githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub commit API error: " + response.statusCode());
        }
        return parseJsonObject(response.body());
    }

    /**
     * Bitbucket Pull Requests 가져오기
     * Bitbucket Cloud API 사용
     */
    private List<Map<String, Object>> fetchBitbucketPullRequests(String workspace, String repo) throws IOException, InterruptedException {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/pullrequests?state=OPEN", workspace, repo);
        
        String auth = Base64.getEncoder().encodeToString((bitbucketUsername + ":" + bitbucketAppPassword).getBytes());
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", "Basic " + auth)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Bitbucket API error: " + response.statusCode());
        }
        
        // JSON 파싱 (values 배열 추출)
        Map<String, Object> json = parseJsonObject(response.body());
        Object values = json.get("values");
        if (values instanceof List) {
            return (List<Map<String, Object>>) values;
        }
        return new ArrayList<>();
    }

    /**
     * Bitbucket Server Pull Requests 가져오기
     * Bitbucket Server REST API 1.0 사용
     * 
     * @param host Bitbucket Server 호스트 URL
     * @param project 프로젝트 키
     * @param repo 저장소 이름
     * @return 열린 PR 목록
     */
    private List<Map<String, Object>> fetchBitbucketServerPullRequests(String host, String project, String repo) throws IOException, InterruptedException {
        String url = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests?state=OPEN", host, project, repo);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + bitbucketServerToken)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Bitbucket Server API error: " + response.statusCode());
        }
        
        // JSON 파싱 (values 배열 추출)
        Map<String, Object> json = parseJsonObject(response.body());
        Object values = json.get("values");
        if (values instanceof List) {
            return (List<Map<String, Object>>) values;
        }
        return new ArrayList<>();
    }

    /**
     * GitHub PR Diff 가져오기
     */
    private StructuredDiff fetchGitHubPRDiff(String owner, String repo, int prNumber) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/%s/pulls/%d", owner, repo, prNumber);
        
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/vnd.github.v3.diff")
                .GET();
        if (githubToken != null && !githubToken.isBlank()) {
            builder.header("Authorization", "Bearer " + githubToken);
        }
        HttpRequest request = builder.build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode());
        }
        
        return parseDiffToStructuredDiff(response.body());
    }

    /**
     * Bitbucket PR Diff 가져오기
     * Bitbucket Cloud API 사용
     */
    private StructuredDiff fetchBitbucketPRDiff(String workspace, String repo, int prNumber) throws IOException, InterruptedException {
        String url = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/pullrequests/%d/diff", workspace, repo, prNumber);
        
        String auth = Base64.getEncoder().encodeToString((bitbucketUsername + ":" + bitbucketAppPassword).getBytes());
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/diff")
            .header("Authorization", "Basic " + auth)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Bitbucket API error: " + response.statusCode());
        }
        
        // Diff 파싱하여 StructuredDiff 로 변환
        return parseDiffToStructuredDiff(response.body());
    }

    /**
     * Bitbucket Server PR Diff 가져오기
     * Bitbucket Server REST API 1.0 사용
     * 
     * @param host Bitbucket Server 호스트 URL
     * @param project 프로젝트 키
     * @param repo 저장소 이름
     * @param prNumber PR 번호
     * @return 파싱된 Diff
     */
    private StructuredDiff fetchBitbucketServerPRDiff(String host, String project, String repo, int prNumber) throws IOException, InterruptedException {
        String url = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/%d/diff", host, project, repo, prNumber);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/diff")
            .header("Authorization", "Bearer " + bitbucketServerToken)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("Bitbucket Server API error: " + response.statusCode());
        }
        
        // Diff 파싱하여 StructuredDiff 로 변환
        return parseDiffToStructuredDiff(response.body());
    }

    /**
     * Conventions 로드
     */
    private Conventions loadConventions() {
        try {
            Path conventionsPath = Paths.get(".reviewbot/conventions.json");
            if (conventionsPath.toFile().exists()) {
                return reviewRunner.loadConventions(conventionsPath);
            }
        } catch (IOException e) {
            log.warn("Failed to load conventions, using defaults: {}", e.getMessage());
        }
        
        // 기본 컨벤션 반환
        return createDefaultConventions();
    }

    /**
     * 리뷰 결과 리포팅
     */
    private void reportResults(String prKey, String prUrl, ReviewResult result) throws IOException {
        Reporter reporter = new Reporter();
        reporter.setLanguage("ko".equals(defaultLang) ? Reporter.Language.KOREAN : Reporter.Language.ENGLISH);
        
        // 출력 형식에 따라 리포트
        switch (defaultFormat.toLowerCase()) {
            case "file":
                Path outputPath = Paths.get(fileOutputDir, "report-" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
                reporter.saveToFile(result, outputPath);
                break;
            case "pr":
                if (prUrl != null && !prUrl.isEmpty()) {
                    reporter.postToPR(prUrl, result);
                }
                break;
            case "all":
                Path filePath = Paths.get(fileOutputDir, "report-" + 
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
                reporter.saveToFile(result, filePath);
                if (prUrl != null && !prUrl.isEmpty()) {
                    reporter.postToPR(prUrl, result);
                }
                break;
            case "terminal":
            default:
                reporter.printToTerminal(result);
                break;
        }
    }

    private Long upsertGitHubPullRequest(String owner, String repo, Map<String, Object> pr) {
        Map<String, Object> user = asMap(pr.get("user"));
        Map<String, Object> item = new HashMap<>();
        item.put("number", pr.get("number"));
        item.put("title", pr.getOrDefault("title", "Untitled pull request"));
        item.put("author", user.getOrDefault("login", "unknown"));
        item.put("provider", "github");
        item.put("repo", owner + "/" + repo);
        item.put("state", pr.getOrDefault("state", "open"));
        item.put("updatedAt", pr.getOrDefault("updated_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        item.put("url", pr.getOrDefault("html_url", ""));
        item.put("reviewStatus", "PENDING");
        return asLong(dashboardDataService.upsertPullRequest(item).get("id"));
    }

    private Long upsertBitbucketPullRequest(String workspace, String repo, Map<String, Object> pr) {
        Map<String, Object> author = asMap(pr.get("author"));
        Map<String, Object> links = asMap(pr.get("links"));
        Map<String, Object> html = asMap(links.get("html"));
        Map<String, Object> item = new HashMap<>();
        item.put("number", pr.get("id"));
        item.put("title", pr.getOrDefault("title", "Untitled pull request"));
        item.put("author", author.getOrDefault("display_name", "unknown"));
        item.put("provider", "bitbucket");
        item.put("repo", workspace + "/" + repo);
        item.put("state", String.valueOf(pr.getOrDefault("state", "OPEN")).toLowerCase());
        item.put("updatedAt", pr.getOrDefault("updated_on", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)));
        item.put("url", html.getOrDefault("href", ""));
        item.put("reviewStatus", "PENDING");
        return asLong(dashboardDataService.upsertPullRequest(item).get("id"));
    }

    private Long upsertBitbucketServerPullRequest(String host, String project, String repo, Map<String, Object> pr) {
        Map<String, Object> author = asMap(asMap(pr.get("author")).get("user"));
        Map<String, Object> item = new HashMap<>();
        item.put("number", pr.get("id"));
        item.put("title", pr.getOrDefault("title", "Untitled pull request"));
        item.put("author", author.getOrDefault("displayName", "unknown"));
        item.put("provider", "bitbucket-server");
        item.put("repo", project + "/" + repo);
        item.put("state", String.valueOf(pr.getOrDefault("state", "OPEN")).toLowerCase());
        item.put("updatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        item.put("url", String.format("%s/projects/%s/repos/%s/pull-requests/%s", host, project, repo, pr.get("id")));
        item.put("reviewStatus", "PENDING");
        return asLong(dashboardDataService.upsertPullRequest(item).get("id"));
    }

    private void recordGitHubBranchHead(String owner, String repo, String branch, Map<String, Object> commit) {
        String sha = String.valueOf(commit.getOrDefault("sha", ""));
        if (sha.isBlank()) {
            return;
        }

        String eventKey = String.format("github/%s/%s/%s/%s", owner, repo, branch, sha);
        if (dashboardDataService.hasHistoryEvent(eventKey)) {
            return;
        }

        Map<String, Object> commitInfo = asMap(commit.get("commit"));
        Map<String, Object> author = asMap(commitInfo.get("author"));
        String message = String.valueOf(commitInfo.getOrDefault("message", ""));
        Map<String, Object> entry = new HashMap<>();
        entry.put("eventKey", eventKey);
        entry.put("eventType", "COMMIT");
        entry.put("repo", owner + "/" + repo);
        entry.put("branch", branch);
        entry.put("commitSha", sha);
        entry.put("title", firstLine(message));
        entry.put("author", author.getOrDefault("name", "unknown"));
        entry.put("url", commit.getOrDefault("html_url", ""));
        entry.put("status", "COMPLETED");
        entry.put("reviewedAt", parseProviderDate(String.valueOf(author.getOrDefault("date", ""))));
        entry.put("violations", List.of());
        entry.put("totalViolations", 0);
        entry.put("errorCount", 0);
        entry.put("warningCount", 0);
        entry.put("infoCount", 0);
        dashboardDataService.addHistoryEntry(entry);
        log.info("Recorded GitHub branch update {}/{} {} {}", owner, repo, branch, sha);
    }

    private Map<String, Object> reviewHistoryEntry(String eventKey, Long prId, int prNumber, String repo,
                                                   String url, ReviewResult result, StructuredDiff diff) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("eventKey", eventKey);
        entry.put("eventType", "REVIEW");
        entry.put("prId", prId);
        entry.put("prNumber", prNumber);
        entry.put("repo", repo);
        entry.put("url", url);
        entry.put("reviewedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        entry.put("violations", violationsToMaps(result));
        entry.put("fileReviews", fileReviewsToMaps(result));
        entry.put("diff", diffToMap(diff));
        entry.put("totalViolations", result.getTotalViolations());
        entry.put("errorCount", countSeverity(result, Severity.ERROR));
        entry.put("warningCount", countSeverity(result, Severity.WARNING));
        entry.put("infoCount", countSeverity(result, Severity.INFO));
        entry.put("status", result.getTotalViolations() == 0 ? "PASSED" : "FAILED");
        return entry;
    }

    /**
     * 설정에서 GitHub repos 가져오기
     */
    private List<Map<String, String>> getGitHubReposFromConfig() {
        List<Map<String, String>> repos = new ArrayList<>();
        dashboardDataService.getConfiguredRepos().stream()
                .filter(repo -> "github".equals(repo.get("provider")))
                .map(this::stringMap)
                .forEach(repos::add);
        
        String owner = System.getenv("GITHUB_OWNER");
        String repo = System.getenv("GITHUB_REPO");
        
        if (owner != null && repo != null) {
            Map<String, String> repoConfig = new HashMap<>();
            repoConfig.put("owner", owner);
            repoConfig.put("repo", repo);
            repos.add(repoConfig);
        }
        
        return repos;
    }

    /**
     * 설정에서 Bitbucket repos 가져오기
     */
    private List<Map<String, String>> getBitbucketReposFromConfig() {
        List<Map<String, String>> repos = new ArrayList<>();
        dashboardDataService.getConfiguredRepos().stream()
                .filter(repo -> "bitbucket".equals(repo.get("provider")) || "bitbucket-cloud".equals(repo.get("provider")))
                .map(this::stringMap)
                .forEach(repos::add);
        
        String workspace = System.getenv("BITBUCKET_WORKSPACE");
        String repo = System.getenv("BITBUCKET_REPO");
        
        if (workspace != null && repo != null) {
            Map<String, String> repoConfig = new HashMap<>();
            repoConfig.put("workspace", workspace);
            repoConfig.put("repo", repo);
            repos.add(repoConfig);
        }
        
        return repos;
    }

    /**
     * 설정에서 Bitbucket Server repos 가져오기
     * application.yml 의 reviewbot.bitbucket-server.repos 에서 읽음
     * 
     * @return Bitbucket Server 설정 목록
     */
    private List<Map<String, String>> getBitbucketServerReposFromConfig() {
        List<Map<String, String>> repos = new ArrayList<>();
        dashboardDataService.getConfiguredRepos().stream()
                .filter(repo -> "bitbucket-server".equals(repo.get("provider")))
                .map(this::stringMap)
                .forEach(repos::add);
        
        String host = System.getenv("BITBUCKET_SERVER_HOST");
        String project = System.getenv("BITBUCKET_SERVER_PROJECT");
        String repo = System.getenv("BITBUCKET_SERVER_REPO");
        
        if (host != null && project != null && repo != null) {
            Map<String, String> repoConfig = new HashMap<>();
            repoConfig.put("host", host);
            repoConfig.put("project", project);
            repoConfig.put("repo", repo);
            repos.add(repoConfig);
        }
        
        return repos;
    }

    /**
     * DateTime 문자열 파싱
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null) {
            return LocalDateTime.now();
        }
        
        try {
            // ISO 8601 형식: 2024-01-01T12:00:00Z
            return OffsetDateTime.parse(dateTimeStr).toLocalDateTime();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(dateTimeStr);
            } catch (Exception ignored) {
            }
            log.warn("Failed to parse datetime: {}", dateTimeStr);
            return LocalDateTime.now();
        }
    }

    /**
     * JSON 배열 파싱
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.warn("Failed to parse JSON array: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /**
     * JSON 객체 파싱
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (IOException e) {
            log.warn("Failed to parse JSON object: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Diff 텍스트를 StructuredDiff 로 파싱
     */
    private StructuredDiff parseDiffToStructuredDiff(String diffText) {
        return new com.reviewbot.diff.DiffAnalyzer().parseDiff(diffText);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return new HashMap<>();
    }

    private Map<String, String> stringMap(Map<String, Object> source) {
        Map<String, String> result = new HashMap<>();
        source.forEach((key, value) -> {
            if (value != null) {
                result.put(key, String.valueOf(value));
            }
        });
        Object branches = source.get("branches");
        if (branches instanceof List<?> branchList) {
            result.put("branches", String.join(",", branchList.stream().map(String::valueOf).toList()));
        }
        return result;
    }

    private List<String> branchesFrom(Map<String, String> repo) {
        String rawBranches = repo.getOrDefault("branches", "main");
        List<String> branches = new ArrayList<>();
        for (String branch : rawBranches.split(",")) {
            if (!branch.isBlank()) {
                branches.add(branch.trim());
            }
        }
        return branches.isEmpty() ? List.of("main") : branches;
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

    private int numberAsInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private String parseProviderDate(String date) {
        if (date == null || date.isBlank()) {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        try {
            return OffsetDateTime.parse(date).toLocalDateTime().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
    }

    private String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "Branch updated";
        }
        int newline = message.indexOf('\n');
        return newline >= 0 ? message.substring(0, newline) : message;
    }

    private List<Map<String, Object>> violationsToMaps(ReviewResult result) {
        List<Map<String, Object>> violations = new ArrayList<>();
        for (FileReview fileReview : result.getFileReviews()) {
            for (Violation violation : fileReview.getViolations()) {
                Map<String, Object> item = new HashMap<>();
                item.put("file", fileReview.getFilePath());
                item.put("lineNumber", violation.getLineNumber());
                item.put("rule", violation.getRule());
                item.put("message", violation.getMessage());
                item.put("severity", violation.getSeverity().name());
                violations.add(item);
            }
        }
        return violations;
    }

    private List<Map<String, Object>> fileReviewsToMaps(ReviewResult result) {
        List<Map<String, Object>> fileReviews = new ArrayList<>();
        for (FileReview fileReview : result.getFileReviews()) {
            Map<String, Object> item = new HashMap<>();
            item.put("filePath", fileReview.getFilePath());
            List<Map<String, Object>> violations = new ArrayList<>();
            for (Violation violation : fileReview.getViolations()) {
                Map<String, Object> violationMap = new HashMap<>();
                violationMap.put("file", fileReview.getFilePath());
                violationMap.put("lineNumber", violation.getLineNumber());
                violationMap.put("rule", violation.getRule());
                violationMap.put("message", violation.getMessage());
                violationMap.put("severity", violation.getSeverity().name());
                violations.add(violationMap);
            }
            item.put("violations", violations);
            fileReviews.add(item);
        }
        return fileReviews;
    }

    private Map<String, Object> diffToMap(StructuredDiff diff) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> files = new ArrayList<>();
        for (FileDiff file : diff.getFiles()) {
            Map<String, Object> item = new HashMap<>();
            item.put("oldPath", file.getOldPath());
            item.put("newPath", file.getNewPath());
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
            item.put("hunks", hunks);
            files.add(item);
        }
        result.put("files", files);
        return result;
    }

    private long countSeverity(ReviewResult result, Severity severity) {
        return result.getFileReviews().stream()
                .flatMap(fileReview -> fileReview.getViolations().stream())
                .filter(violation -> violation.getSeverity() == severity)
                .count();
    }

    /**
     * 기본 컨벤션 생성
     */
    private Conventions createDefaultConventions() {
        Conventions conventions = new Conventions();
        conventions.setImportOrder(List.of("java.", "javax.", "org.", "com."));
        
        Map<String, Object> namingPatterns = new HashMap<>();
        namingPatterns.put("namingStyle", "camelCase");
        conventions.setNamingPatterns(namingPatterns);
        
        Map<String, Object> formattingRules = new HashMap<>();
        formattingRules.put("indentSpaces", 4);
        formattingRules.put("braceStyle", "sameLine");
        formattingRules.put("maxLineLength", 120);
        conventions.setFormattingRules(formattingRules);
        
        conventions.setCommonPatterns(List.of("System.out.println"));
        
        return conventions;
    }
}
