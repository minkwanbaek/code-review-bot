package com.reviewbot.scheduler;

import com.reviewbot.convention.Conventions;
import com.reviewbot.diff.StructuredDiff;
import com.reviewbot.report.Reporter;
import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.ReviewRunner;
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

    @Value("${reviewbot.output.default-lang:ko}")
    private String defaultLang;

    @Value("${reviewbot.output.default-format:terminal}")
    private String defaultFormat;

    @Value("${reviewbot.output.file-output-dir:./reports}")
    private String fileOutputDir;

    private final ReviewRunner reviewRunner = new ReviewRunner();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    
    // 마지막으로 확인한 PR 업데이트 시간 추적 (중복 리뷰 방지)
    private final Map<String, LocalDateTime> lastCheckedPRs = new ConcurrentHashMap<>();

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
            
            // Bitbucket PRs 확인
            pollBitbucketPullRequests();
            
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
        
        // 설정에서 GitHub repos 가져오기 (실제로는 설정 서비스에서 로드)
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

    /**
     * Bitbucket Pull Requests 폴링
     */
    private void pollBitbucketPullRequests() {
        log.debug("Polling Bitbucket pull requests...");
        
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
     * GitHub PR 처리
     */
    private void processGitHubPR(String owner, String repo, Map<String, Object> pr) {
        int prNumber = (Integer) pr.get("number");
        String prKey = String.format("github/%s/%s/%d", owner, repo, prNumber);
        String updatedAt = (String) pr.get("updated_at");
        
        LocalDateTime prUpdatedAt = parseDateTime(updatedAt);
        LocalDateTime lastChecked = lastCheckedPRs.get(prKey);
        
        // 이미 확인한 PR 이고 업데이트되지 않았으면 스킵
        if (lastChecked != null && !prUpdatedAt.isAfter(lastChecked)) {
            log.debug("PR {} already reviewed, skipping", prKey);
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
        int prNumber = (Integer) pr.get("id");
        String prKey = String.format("bitbucket/%s/%s/%d", workspace, repo, prNumber);
        String updatedAt = (String) pr.get("updated_on");
        
        LocalDateTime prUpdatedAt = parseDateTime(updatedAt);
        LocalDateTime lastChecked = lastCheckedPRs.get(prKey);
        
        // 이미 확인한 PR 이고 업데이트되지 않았으면 스킵
        if (lastChecked != null && !prUpdatedAt.isAfter(lastChecked)) {
            log.debug("PR {} already reviewed, skipping", prKey);
            return;
        }
        
        log.info("Processing Bitbucket PR #{} from {}/{}", prNumber, workspace, repo);
        
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
            
            // 마지막 확인 시간 업데이트
            lastCheckedPRs.put(prKey, prUpdatedAt);
            
            log.info("Completed review for Bitbucket PR #{} - {} violations found", prNumber, result.getTotalViolations());
        } catch (Exception e) {
            log.error("Error processing Bitbucket PR #{}: {}", prNumber, e.getMessage());
        }
    }

    /**
     * GitHub Pull Requests 가져오기
     */
    private List<Map<String, Object>> fetchGitHubPullRequests(String owner, String repo) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/%s/pulls?state=open&per_page=100", owner, repo);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer " + githubToken)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode());
        }
        
        // JSON 파싱 (실제로는 Jackson 사용)
        return parseJsonArray(response.body());
    }

    /**
     * Bitbucket Pull Requests 가져오기
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
     * GitHub PR Diff 가져오기
     */
    private StructuredDiff fetchGitHubPRDiff(String owner, String repo, int prNumber) throws IOException, InterruptedException {
        String url = String.format("https://api.github.com/repos/%s/%s/pulls/%d/files", owner, repo, prNumber);
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/vnd.github.v3.diff")
            .header("Authorization", "Bearer " + githubToken)
            .GET()
            .build();
        
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("GitHub API error: " + response.statusCode());
        }
        
        // Diff 파싱하여 StructuredDiff 로 변환
        return parseDiffToStructuredDiff(response.body());
    }

    /**
     * Bitbucket PR Diff 가져오기
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

    /**
     * 설정에서 GitHub repos 가져오기
     */
    private List<Map<String, String>> getGitHubReposFromConfig() {
        // 실제로는 application.yml 의 reviewbot.repos 에서 읽음
        // 여기서는 샘플 데이터 반환
        List<Map<String, String>> repos = new ArrayList<>();
        
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
        // 실제로는 application.yml 의 reviewbot.repos 에서 읽음
        List<Map<String, String>> repos = new ArrayList<>();
        
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
     * DateTime 문자열 파싱
     */
    private LocalDateTime parseDateTime(String dateTimeStr) {
        if (dateTimeStr == null) {
            return LocalDateTime.now();
        }
        
        try {
            // ISO 8601 형식: 2024-01-01T12:00:00Z
            return LocalDateTime.parse(dateTimeStr.replace("Z", "").replace("T", " "));
        } catch (Exception e) {
            log.warn("Failed to parse datetime: {}", dateTimeStr);
            return LocalDateTime.now();
        }
    }

    /**
     * JSON 배열 파싱 (스켈레톤 - 실제로는 Jackson 사용)
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseJsonArray(String json) {
        // 실제 구현에서는 Jackson ObjectMapper 사용
        return new ArrayList<>();
    }

    /**
     * JSON 객체 파싱 (스켈레톤 - 실제로는 Jackson 사용)
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json) {
        // 실제 구현에서는 Jackson ObjectMapper 사용
        return new HashMap<>();
    }

    /**
     * Diff 텍스트를 StructuredDiff 로 파싱
     */
    private StructuredDiff parseDiffToStructuredDiff(String diffText) {
        // 실제 구현에서는 DiffAnalyzer 사용
        // 여기서는 빈 객체 반환
        return new StructuredDiff();
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
