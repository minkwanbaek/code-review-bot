package com.reviewbot.report;

import com.reviewbot.review.ReviewResult;
import com.reviewbot.review.FileReview;
import com.reviewbot.review.Violation;
import com.reviewbot.review.Severity;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * US-4: Multi-output reporter
 * 터미널 출력 / 파일 저장 / GitHub Bitbucket PR 자동 코멘트 지원. 한국어/영어 선택
 */
public class Reporter {

    private static final Logger log = LoggerFactory.getLogger(Reporter.class);
    
    private OutputFormat outputFormat = OutputFormat.TERMINAL;
    private Language language = Language.KOREAN;
    private String githubToken;
    private String bitbucketUsername;
    private String bitbucketAppPassword;
    private String bitbucketServerToken;

    public enum OutputFormat {
        TERMINAL, FILE, PR, ALL
    }

    public enum Language {
        KOREAN, ENGLISH
    }

    public Reporter() {
        // 환경 변수에서 토큰 로드
        this.githubToken = System.getenv("GITHUB_TOKEN");
        this.bitbucketUsername = System.getenv("BITBUCKET_USERNAME");
        this.bitbucketAppPassword = System.getenv("BITBUCKET_APP_PASSWORD");
        this.bitbucketServerToken = System.getenv("BITBUCKET_SERVER_TOKEN");
    }

    public Reporter setOutputFormat(OutputFormat format) {
        this.outputFormat = format;
        return this;
    }

    public Reporter setLanguage(Language lang) {
        this.language = lang;
        return this;
    }

    public Reporter setGithubToken(String token) {
        this.githubToken = token;
        return this;
    }

    public Reporter setBitbucketCredentials(String username, String appPassword) {
        this.bitbucketUsername = username;
        this.bitbucketAppPassword = appPassword;
        return this;
    }

    public Reporter setBitbucketServerToken(String token) {
        this.bitbucketServerToken = token;
        return this;
    }

    /**
     * 리뷰 결과 리포트 생성 및 출력
     * 
     * @param result 리뷰 결과
     * @param outputPath 출력 파일 경로 (FILE 모드일 때 사용)
     * @param prUrl PR URL (PR 모드일 때 사용)
     * @throws IOException 입출력 오류 시
     */
    public void report(ReviewResult result, Path outputPath, String prUrl) throws IOException {
        String reportContent = generateReport(result);
        
        switch (outputFormat) {
            case TERMINAL:
                printToTerminal(result);
                break;
            case FILE:
                if (outputPath == null) {
                    outputPath = Paths.get(".reviewbot", "report-" + 
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
                }
                saveToFile(result, outputPath);
                break;
            case PR:
                if (prUrl != null && !prUrl.isEmpty()) {
                    postToPR(prUrl, result);
                } else {
                    log.warn("PR URL not provided. Cannot post to PR.");
                }
                break;
            case ALL:
                printToTerminal(result);
                if (outputPath == null) {
                    outputPath = Paths.get(".reviewbot", "report-" + 
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
                }
                saveToFile(result, outputPath);
                if (prUrl != null && !prUrl.isEmpty()) {
                    postToPR(prUrl, result);
                }
                break;
        }
    }

    /**
     * 리뷰 결과 리포트 생성
     * 
     * @param result 리뷰 결과
     * @return 생성된 리포트 문자열
     */
    public String generateReport(ReviewResult result) {
        StringBuilder report = new StringBuilder();
        
        if (language == Language.KOREAN) {
            report.append("# 코드 리뷰 결과\n\n");
            report.append(String.format("**생성일시:** %s\n\n", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            
            if (!result.hasViolations()) {
                report.append("## ✅ 결과\n");
                report.append("모든 변경사항이 컨벤션을 준수합니다!\n\n");
                report.append("### 📊 요약\n");
                report.append("- 검토된 파일: 0 개\n");
                report.append("- 총 위반 항목: 0 개\n");
            } else {
                report.append(String.format("## ⚠️ 총 %d개의 위반 항목 발견\n\n", result.getTotalViolations()));
                
                // 심각도별 통계
                Map<Severity, Long> severityCount = countBySeverity(result);
                report.append("### 📊 심각도별 통계\n");
                report.append(String.format("- 🔴 ERROR: %d개\n", severityCount.getOrDefault(Severity.ERROR, 0L)));
                report.append(String.format("- 🟡 WARNING: %d개\n", severityCount.getOrDefault(Severity.WARNING, 0L)));
                report.append(String.format("- 🔵 INFO: %d개\n\n", severityCount.getOrDefault(Severity.INFO, 0L)));
                
                // 파일별 상세
                for (FileReview fileReview : result.getFileReviews()) {
                    report.append(String.format("### 📁 %s\n", fileReview.getFilePath()));
                    report.append(String.format("**위반 항목:** %d개\n\n", fileReview.getViolationCount()));
                    
                    for (Violation violation : fileReview.getViolations()) {
                        report.append(String.format("- **[%s]** `%s` (라인 %d): %s\n",
                            getSeverityLabel(violation.getSeverity()),
                            violation.getRule(),
                            violation.getLineNumber(),
                            violation.getMessage()));
                    }
                    report.append("\n");
                }
            }
            
            report.append("---\n");
            report.append("*Code Review Bot by Ralph Loop Core*\n");
        } else {
            report.append("# Code Review Report\n\n");
            report.append(String.format("**Generated:** %s\n\n", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            
            if (!result.hasViolations()) {
                report.append("## ✅ Result\n");
                report.append("All changes comply with conventions!\n\n");
                report.append("### 📊 Summary\n");
                report.append("- Files reviewed: 0\n");
                report.append("- Total violations: 0\n");
            } else {
                report.append(String.format("## ⚠️ Found %d violations\n\n", result.getTotalViolations()));
                
                // Severity statistics
                Map<Severity, Long> severityCount = countBySeverity(result);
                report.append("### 📊 Severity Breakdown\n");
                report.append(String.format("- 🔴 ERROR: %d\n", severityCount.getOrDefault(Severity.ERROR, 0L)));
                report.append(String.format("- 🟡 WARNING: %d\n", severityCount.getOrDefault(Severity.WARNING, 0L)));
                report.append(String.format("- 🔵 INFO: %d\n\n", severityCount.getOrDefault(Severity.INFO, 0L)));
                
                // Details by file
                for (FileReview fileReview : result.getFileReviews()) {
                    report.append(String.format("### 📁 %s\n", fileReview.getFilePath()));
                    report.append(String.format("**Violations:** %d\n\n", fileReview.getViolationCount()));
                    
                    for (Violation violation : fileReview.getViolations()) {
                        report.append(String.format("- **[%s]** `%s` (line %d): %s\n",
                            getSeverityLabel(violation.getSeverity()),
                            violation.getRule(),
                            violation.getLineNumber(),
                            violation.getMessage()));
                    }
                    report.append("\n");
                }
            }
            
            report.append("---\n");
            report.append("*Code Review Bot by Ralph Loop Core*\n");
        }
        
        return report.toString();
    }

    /**
     * 심각도별 카운트
     */
    private Map<Severity, Long> countBySeverity(ReviewResult result) {
        Map<Severity, Long> counts = new EnumMap<>(Severity.class);
        for (FileReview fileReview : result.getFileReviews()) {
            for (Violation violation : fileReview.getViolations()) {
                counts.merge(violation.getSeverity(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /**
     * 터미널에 출력 (ANSI 색상 지원)
     * 
     * @param result 리뷰 결과
     */
    public void printToTerminal(ReviewResult result) {
        String report = generateReport(result);
        
        // ANSI 색상 코드로 포맷팅
        String coloredReport = applyAnsiColors(report);
        System.out.println(coloredReport);
    }

    /**
     * ANSI 색상 적용
     */
    private String applyAnsiColors(String report) {
        return report
            .replace("🔴 ERROR", "\u001B[31m🔴 ERROR\u001B[0m")  // Red
            .replace("🟡 WARNING", "\u001B[33m🟡 WARNING\u001B[0m")  // Yellow
            .replace("🔵 INFO", "\u001B[34m🔵 INFO\u001B[0m")  // Blue
            .replace("[오류]", "\u001B[31m[오류]\u001B[0m")
            .replace("[경고]", "\u001B[33m[경고]\u001B[0m")
            .replace("[정보]", "\u001B[34m[정보]\u001B[0m")
            .replace("[ERROR]", "\u001B[31m[ERROR]\u001B[0m")
            .replace("[WARNING]", "\u001B[33m[WARNING]\u001B[0m")
            .replace("[INFO]", "\u001B[34m[INFO]\u001B[0m")
            .replace("✅", "\u001B[32m✅\u001B[0m")  // Green
            .replace("⚠️", "\u001B[33m⚠️\u001B[0m");  // Yellow
    }

    /**
     * 파일로 저장
     * 
     * @param result 리뷰 결과
     * @param outputPath 저장할 파일 경로
     * @throws IOException 파일 쓰기 실패 시
     */
    public void saveToFile(ReviewResult result, Path outputPath) throws IOException {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, generateReport(result));
        log.info("Report saved to: {}", outputPath.toAbsolutePath());
    }

    /**
     * PR 에 코멘트 (GitHub 또는 Bitbucket 자동 감지)
     * 
     * @param prUrl PR URL
     * @param result 리뷰 결과
     * @throws IOException API 호출 실패 시
     */
    public void postToPR(String prUrl, ReviewResult result) throws IOException {
        if (prUrl.contains("github.com")) {
            postToGitHubPR(prUrl, result);
        } else if (prUrl.contains("bitbucket.org")) {
            postToBitbucketPR(prUrl, result);
        } else if (prUrl.contains("bitbucket")) {
            // Bitbucket Server 감지 (온프레미스)
            postToBitbucketServerPR(prUrl, result);
        } else {
            throw new IllegalArgumentException("Unsupported PR URL. Only GitHub, Bitbucket Cloud, and Bitbucket Server are supported.");
        }
    }

    /**
     * GitHub PR 에 코멘트
     * 
     * @param prUrl GitHub PR URL (형식: https://github.com/{owner}/{repo}/pull/{number})
     * @param result 리뷰 결과
     * @throws IOException API 호출 실패 시
     */
    public void postToGitHubPR(String prUrl, ReviewResult result) throws IOException {
        if (githubToken == null || githubToken.isEmpty()) {
            log.error("GitHub token not configured. Set GITHUB_TOKEN environment variable.");
            throw new IllegalStateException("GitHub token not configured");
        }

        // URL 에서 owner, repo, PR 번호 추출
        String[] parts = prUrl.split("/");
        if (parts.length < 7) {
            throw new IllegalArgumentException("Invalid GitHub PR URL format");
        }
        String owner = parts[3];
        String repo = parts[4];
        String prNumber = parts[6];

        String apiEndpoint = String.format("https://api.github.com/repos/%s/%s/issues/%s/comments", owner, repo, prNumber);
        String commentBody = generateReport(result);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(apiEndpoint);
            post.setHeader("Authorization", "Bearer " + githubToken);
            post.setHeader("Accept", "application/vnd.github.v3+json");
            post.setHeader("Content-Type", "application/json");

            String jsonBody = String.format("{\"body\": %s}", escapeJson(commentBody));
            post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            var response = httpClient.execute(post);
            int statusCode = response.getCode();
            
            if (statusCode >= 200 && statusCode < 300) {
                log.info("Successfully posted comment to GitHub PR #{}", prNumber);
            } else {
                log.error("Failed to post to GitHub PR. Status: {}", statusCode);
                throw new IOException("GitHub API error: " + statusCode);
            }
        }
    }

    /**
     * Bitbucket PR 에 코멘트
     * Bitbucket Cloud API 사용
     * 
     * @param prUrl Bitbucket PR URL (형식: https://bitbucket.org/{workspace}/{repo}/pull-requests/{number})
     * @param result 리뷰 결과
     * @throws IOException API 호출 실패 시
     */
    public void postToBitbucketPR(String prUrl, ReviewResult result) throws IOException {
        if (bitbucketUsername == null || bitbucketAppPassword == null) {
            log.error("Bitbucket credentials not configured. Set BITBUCKET_USERNAME and BITBUCKET_APP_PASSWORD.");
            throw new IllegalStateException("Bitbucket credentials not configured");
        }

        // URL 에서 workspace, repo, PR 번호 추출
        String[] parts = prUrl.split("/");
        if (parts.length < 7) {
            throw new IllegalArgumentException("Invalid Bitbucket PR URL format");
        }
        String workspace = parts[3];
        String repo = parts[4];
        String prNumber = parts[6];

        String apiEndpoint = String.format("https://api.bitbucket.org/2.0/repositories/%s/%s/pullrequests/%s/comments", 
            workspace, repo, prNumber);
        String commentBody = generateReport(result);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(apiEndpoint);
            String authHeader = Base64.getEncoder().encodeToString((bitbucketUsername + ":" + bitbucketAppPassword).getBytes());
            post.setHeader("Authorization", "Basic " + authHeader);
            post.setHeader("Content-Type", "application/json");

            String jsonBody = String.format("{\"content\": {\"raw\": %s}}", escapeJson(commentBody));
            post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            var response = httpClient.execute(post);
            int statusCode = response.getCode();
            
            if (statusCode >= 200 && statusCode < 300) {
                log.info("Successfully posted comment to Bitbucket Cloud PR #{}", prNumber);
            } else {
                log.error("Failed to post to Bitbucket Cloud PR. Status: {}", statusCode);
                throw new IOException("Bitbucket API error: " + statusCode);
            }
        }
    }

    /**
     * Bitbucket Server PR 에 코멘트
     * Bitbucket Server REST API 1.0 사용
     * 
     * @param prUrl Bitbucket Server PR URL (형식: https://{host}/projects/{project}/repos/{repo}/pull-requests/{number})
     * @param result 리뷰 결과
     * @throws IOException API 호출 실패 시
     */
    public void postToBitbucketServerPR(String prUrl, ReviewResult result) throws IOException {
        if (bitbucketServerToken == null || bitbucketServerToken.isEmpty()) {
            log.error("Bitbucket Server token not configured. Set BITBUCKET_SERVER_TOKEN.");
            throw new IllegalStateException("Bitbucket Server token not configured");
        }

        // URL 에서 host, project, repo, PR 번호 추출
        // 형식: https://{host}/projects/{project}/repos/{repo}/pull-requests/{number}
        String[] parts = prUrl.split("/");
        if (parts.length < 9) {
            throw new IllegalArgumentException("Invalid Bitbucket Server PR URL format");
        }
        
        // host 추출 (https: 다음)
        String host = parts[0] + "//" + parts[2];
        // project 는 "projects" 다음
        String project = parts[4];
        // repo 는 "repos" 다음
        String repo = parts[6];
        // PR 번호는 마지막 부분
        String prNumber = parts[8];

        String apiEndpoint = String.format("%s/rest/api/1.0/projects/%s/repos/%s/pull-requests/%s/comments", 
            host, project, repo, prNumber);
        String commentBody = generateReport(result);

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(apiEndpoint);
            post.setHeader("Authorization", "Bearer " + bitbucketServerToken);
            post.setHeader("Content-Type", "application/json");

            String jsonBody = String.format("{\"text\": %s}", escapeJson(commentBody));
            post.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            var response = httpClient.execute(post);
            int statusCode = response.getCode();
            
            if (statusCode >= 200 && statusCode < 300) {
                log.info("Successfully posted comment to Bitbucket Server PR #{}", prNumber);
            } else {
                log.error("Failed to post to Bitbucket Server PR. Status: {}", statusCode);
                throw new IOException("Bitbucket Server API error: " + statusCode);
            }
        }
    }

    /**
     * JSON 문자열 이스케이프
     */
    private String escapeJson(String text) {
        return "\"" + text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t") + "\"";
    }

    /**
     * 심각도 라벨 반환
     */
    private String getSeverityLabel(Severity severity) {
        if (language == Language.KOREAN) {
            return switch (severity) {
                case ERROR -> "오류";
                case WARNING -> "경고";
                case INFO -> "정보";
            };
        } else {
            return severity.name();
        }
    }

    /**
     * CLI 옵션 파싱
     * 
     * @param args 명령줄 인수
     * @return 설정된 Reporter 인스턴스
     */
    public static Reporter fromArgs(String[] args) {
        Reporter reporter = new Reporter();
        
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--output":
                    if (i + 1 < args.length) {
                        String format = args[++i].toUpperCase();
                        try {
                            reporter.setOutputFormat(OutputFormat.valueOf(format));
                        } catch (IllegalArgumentException e) {
                            System.err.println("Invalid output format: " + format);
                        }
                    }
                    break;
                case "--lang":
                    if (i + 1 < args.length) {
                        String lang = args[++i].toLowerCase();
                        if ("ko".equals(lang) || "korean".equals(lang)) {
                            reporter.setLanguage(Language.KOREAN);
                        } else if ("en".equals(lang) || "english".equals(lang)) {
                            reporter.setLanguage(Language.ENGLISH);
                        }
                    }
                    break;
                case "--github-token":
                    if (i + 1 < args.length) {
                        reporter.setGithubToken(args[++i]);
                    }
                    break;
                case "--bitbucket-user":
                    if (i + 1 < args.length) {
                        String username = args[++i];
                        String password = (i + 2 < args.length) ? args[++i] : "";
                        reporter.setBitbucketCredentials(username, password);
                    }
                    break;
            }
        }
        
        return reporter;
    }
}
