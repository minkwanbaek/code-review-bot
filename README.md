# Code Review Bot 🤖

Ralph Loop 방식으로 자동 개발된 Java 기반 코드 리뷰 봇입니다.

## 기능

- ✅ **Git Diff 분석**: 로컬 git repo 및 GitHub/Bitbucket PR 의 diff 를 구조화된 형태로 변환
- ✅ **Convention 학습**: 대상 repository 의 코드 스타일/컨벤션 자동 추출
- ✅ **자동 리뷰**: 학습된 컨벤션 기준으로 코드 변경사항 검사
- ✅ **다국어 리포트**: 한국어/영어 지원, 터미널/파일/PR 코멘트 출력
- ✅ **Docker 배포**: 간편한 컨테이너 배포 지원
- ✅ **자동 Polling**: 설정된 interval 로 PR 자동 모니터링 및 리뷰

## 기술 스택

- **Language**: Java 21
- **Framework**: Spring Boot 3.x + Thymeleaf
- **Build Tool**: Gradle
- **Test Framework**: JUnit 5 + AssertJ
- **Git Support**: Eclipse JGit
- **Deployment**: Docker + Docker Compose

## 빠른 시작

### 1. 로컬 실행

```bash
cd reviewbot
./gradlew bootRun
```

### 2. Docker 실행

```bash
docker compose up -d
```

http://localhost:8080 에서 접속 가능합니다.

### 3. 환경 변수 설정

`.env` 파일 생성:

```bash
GITHUB_TOKEN=your_github_token
BITBUCKET_USERNAME=your_username
BITBUCKET_APP_PASSWORD=your_app_password
REVIEWBOT_POLL_INTERVAL=300
REVIEWBOT_POLL_ENABLED=true
```

## 사용법

### Git Diff 분석 (US-1)

```java
DiffAnalyzer analyzer = new DiffAnalyzer();

// 로컬 diff 분석
StructuredDiff diff = analyzer.analyzeLocalDiff(
    Paths.get("/path/to/repo"), 
    "HEAD~1"
);

// GitHub PR 분석
StructuredDiff prDiff = analyzer.analyzeGitHubPR(
    "https://github.com/owner/repo/pull/123",
    "ghp_xxx"
);

// JSON 으로 변환
String json = analyzer.toJson(diff);
```

### Convention 학습 (US-2)

```java
ConventionLearner learner = new ConventionLearner();

// Repository 분석
Conventions conventions = learner.analyzeRepository(
    Paths.get("/path/to/repo")
);

// conventions.json 저장
learner.saveConventions(conventions, 
    Paths.get(".reviewbot/conventions.json")
);
```

### 리뷰 실행 (US-3)

```java
ReviewRunner runner = new ReviewRunner();

// Diff 와 conventions 로드
StructuredDiff diff = ...; // US-1 에서 생성
Conventions conventions = ...; // US-2 에서 생성

// 리뷰 수행
ReviewResult result = runner.review(diff, conventions);

// 결과 확인
if (result.hasViolations()) {
    System.out.println("Found " + result.getTotalViolations() + " violations");
}
```

### 리포트 출력 (US-4)

```java
Reporter reporter = new Reporter()
    .setOutputFormat(Reporter.OutputFormat.TERMINAL)
    .setLanguage(Reporter.Language.KOREAN);

// 터미널 출력
reporter.printToTerminal(result);

// 파일 저장
reporter.saveToFile(result, Paths.get("review-report.md"));

// GitHub PR 코멘트 (TODO 구현 필요)
// reporter.postToGitHubPR(prUrl, result, token);
```

## 프로젝트 구조

```
reviewbot/
├── src/main/java/com/reviewbot/
│   ├── ReviewBotApplication.java    # Spring Boot 메인 클래스
│   ├── diff/                        # US-1: Git diff 분석
│   │   ├── DiffAnalyzer.java
│   │   ├── StructuredDiff.java
│   │   ├── FileDiff.java
│   │   ├── Hunk.java
│   │   ├── Change.java
│   │   └── ChangeType.java
│   ├── convention/                  # US-2: Convention 학습
│   │   ├── ConventionLearner.java
│   │   └── Conventions.java
│   ├── review/                      # US-3: 리뷰 실행
│   │   ├── ReviewRunner.java
│   │   ├── ReviewResult.java
│   │   ├── FileReview.java
│   │   ├── Violation.java
│   │   └── Severity.java
│   ├── report/                      # US-4: 리포트 출력
│   │   └── Reporter.java
│   ├── scheduler/                   # US-6: PR polling
│   │   └── PRPollScheduler.java
│   └── config/                      # 설정
│       └── ReviewBotConfig.java
├── src/main/resources/
│   └── application.yml
├── src/test/java/                   # 테스트
├── build.gradle
├── Dockerfile                       # US-5: Docker
└── docker-compose.yml               # US-5: Docker Compose
```

## 현재 구현 상태

| User Story | 제목 | 상태 |
|------------|------|------|
| US-1 | Git diff analyzer | ✅ 구현 완료 |
| US-2 | Convention learner | ✅ 구현 완료 |
| US-3 | Review runner | ⚠️ 기본 구현 (JSON 파싱 필요) |
| US-4 | Multi-output reporter | ⚠️ 기본 구현 (PR 코멘트 필요) |
| US-5 | Docker deployment | ✅ 구현 완료 |
| US-6 | PR poll scheduler | ⚠️ 스켈레톤 (로직 필요) |

## 다음 단계

1. **US-3 완성**: JSON diff/conventions 파싱 구현
2. **US-4 완성**: GitHub/Bitbucket PR 코멘트 API 연동
3. **US-6 완성**: 실제 PR polling 로직 구현
4. **Web UI**: Thymeleaf 기반 대시보드 추가
5. **확장**: GitLab, Azure DevOps 지원 추가

## 테스트

```bash
./gradlew test
```

## 라이선스

MIT License

---

_이 프로젝트는 Ralph Loop 방식으로 자동 개발되었습니다._
