package com.reviewbot.report;

import com.reviewbot.review.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * US-4: Multi-output reporter 테스트
 */
class ReporterTest {

    @TempDir
    Path tempDir;
    
    private Reporter reporter;
    private ReviewResult reviewResult;
    private final ByteArrayOutputStream outContent = new ByteArrayOutputStream();

    @BeforeEach
    void setUp() {
        reporter = new Reporter();
        reviewResult = createSampleReviewResult();
        
        // System.out 캡처
        System.setOut(new PrintStream(outContent));
    }

    @Test
    @DisplayName("터미널 출력 모드로 리포트를 생성한다")
    void report_terminalOutput_printsToConsole() {
        // given
        reporter.setOutputFormat(Reporter.OutputFormat.TERMINAL);
        
        // when
        reporter.printToTerminal(reviewResult);
        
        // then
        String output = outContent.toString();
        assertThat(output).contains("# 코드 리뷰 결과");
        assertThat(output).contains("위반 항목");
        assertThat(output).contains("TestFile.java");
    }

    @Test
    @DisplayName("파일로 리포트를 저장한다")
    void report_fileOutput_savesToFile() throws IOException {
        // given
        Path outputPath = tempDir.resolve("test-report.md");
        reporter.setOutputFormat(Reporter.OutputFormat.FILE);
        
        // when
        reporter.saveToFile(reviewResult, outputPath);
        
        // then
        assertThat(Files.exists(outputPath)).isTrue();
        String content = Files.readString(outputPath);
        assertThat(content).contains("# 코드 리뷰 결과");
        assertThat(content).contains("위반 항목");
    }

    @Test
    @DisplayName("영어 모드로 리포트를 생성한다")
    void report_englishLanguage_generatesEnglishReport() {
        // given
        reporter.setLanguage(Reporter.Language.ENGLISH);
        
        // when
        String report = reporter.generateReport(reviewResult);
        
        // then
        assertThat(report).contains("# Code Review Report");
        assertThat(report).contains("violations");
        assertThat(report).doesNotContain("코드 리뷰 결과");
    }

    @Test
    @DisplayName("한국어 모드로 리포트를 생성한다")
    void report_koreanLanguage_generatesKoreanReport() {
        // given
        reporter.setLanguage(Reporter.Language.KOREAN);
        
        // when
        String report = reporter.generateReport(reviewResult);
        
        // then
        assertThat(report).contains("# 코드 리뷰 결과");
        assertThat(report).contains("위반 항목");
    }

    @Test
    @DisplayName("위반이 없는 경우 성공 메시지를 표시한다")
    void report_noViolations_showsSuccessMessage() {
        // given
        ReviewResult cleanResult = new ReviewResult();
        reporter.setLanguage(Reporter.Language.KOREAN);
        
        // when
        String report = reporter.generateReport(cleanResult);
        
        // then
        assertThat(report).contains("✅");
        assertThat(report).contains("모든 변경사항이 컨벤션을 준수합니다");
    }

    @Test
    @DisplayName("심각도별 통계를 포함한다")
    void report_includesSeverityBreakdown() {
        // when
        String report = reporter.generateReport(reviewResult);
        
        // then
        assertThat(report).contains("ERROR");
        assertThat(report).contains("WARNING");
        assertThat(report).contains("INFO");
    }

    @Test
    @DisplayName("CLI 인수를 파싱한다")
    void fromArgs_parsesCommandLineArguments() {
        // given
        String[] args = {"--output", "file", "--lang", "en"};
        
        // when
        Reporter parsedReporter = Reporter.fromArgs(args);
        
        // then
        assertThat(parsedReporter).isNotNull();
    }

    @Test
    @DisplayName("GitHub 토큰이 없으면 예외를 발생시킨다")
    void postToGitHubPR_withoutToken_throwsException() {
        // given
        reporter.setGithubToken(null);
        
        // when & then
        assertThatThrownBy(() -> reporter.postToGitHubPR("https://github.com/owner/repo/pull/1", reviewResult))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("GitHub token not configured");
    }

    @Test
    @DisplayName("Bitbucket 인증 정보가 없으면 예외를 발생시킨다")
    void postToBitbucketPR_withoutCredentials_throwsException() {
        // given
        reporter.setBitbucketCredentials(null, null);
        
        // when & then
        assertThatThrownBy(() -> reporter.postToBitbucketPR("https://bitbucket.org/workspace/repo/pull-requests/1", reviewResult))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Bitbucket credentials not configured");
    }

    @Test
    @DisplayName("지원하지 않는 PR URL 이면 예외를 발생시킨다")
    void postToPR_unsupportedUrl_throwsException() {
        // given
        String invalidUrl = "https://gitlab.com/owner/repo/merge_requests/1";
        
        // when & then
        assertThatThrownBy(() -> reporter.postToPR(invalidUrl, reviewResult))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported PR URL");
    }

    @Test
    @DisplayName("파일별 위반 항목을 포맷팅한다")
    void report_formatsFileViolations() {
        // when
        String report = reporter.generateReport(reviewResult);
        
        // then
        assertThat(report).contains("📁 TestFile.java");
        assertThat(report).contains("라인 10");
        assertThat(report).contains("NAMING_CONVENTION");
    }

    @Test
    @DisplayName("타임스탬프를 포함한다")
    void report_includesTimestamp() {
        // when
        String report = reporter.generateReport(reviewResult);
        
        // then
        assertThat(report).containsPattern("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    /**
     * 샘플 리뷰 결과 생성
     */
    private ReviewResult createSampleReviewResult() {
        ReviewResult result = new ReviewResult();
        
        FileReview fileReview = new FileReview("TestFile.java");
        fileReview.addViolation(new Violation(Severity.ERROR, "SYNTAX_ERROR", "Missing semicolon", 10));
        fileReview.addViolation(new Violation(Severity.WARNING, "NAMING_CONVENTION", "Variable name should be camelCase", 15));
        fileReview.addViolation(new Violation(Severity.INFO, "INDENTATION", "Incorrect indentation", 20));
        
        result.addFileReview(fileReview);
        
        return result;
    }
}
