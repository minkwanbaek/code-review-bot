package com.reviewbot.features.diff.service;

import com.reviewbot.diff.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US-1: Git diff analyzer
 * 로컬 git repo 와 GitHub/Bitbucket PR URL 에서 diff 를 가져와 구조화된 형태로 변환
 */
public class DiffAnalyzer {

    private static final Pattern FILE_PATTERN = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final Pattern HUNK_PATTERN = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");
    private static final Pattern ADDITION_PATTERN = Pattern.compile("^\\+");
    private static final Pattern DELETION_PATTERN = Pattern.compile("^-");

    /**
     * 로컬 git repository 에서 diff 분석
     */
    public StructuredDiff analyzeLocalDiff(Path repoPath, String commitRange) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "-C", repoPath.toString(), "diff", commitRange);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder diffOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diffOutput.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("Git diff failed with exit code: " + exitCode);
        }

        return parseDiff(diffOutput.toString());
    }

    /**
     * GitHub PR URL 에서 diff 분석
     */
    public StructuredDiff analyzeGitHubPR(String prUrl, String token) throws IOException {
        // Extract owner, repo, PR number from URL
        // Format: https://github.com/owner/repo/pull/123
        String[] parts = prUrl.split("/");
        if (parts.length < 7) {
            throw new IOException("Invalid GitHub PR URL format");
        }
        String owner = parts[parts.length - 4];
        String repo = parts[parts.length - 3];
        String prNumber = parts[parts.length - 1];

        // Fetch diff from GitHub API
        java.net.URL url = new java.net.URL("https://api.github.com/repos/" + owner + "/" + repo + "/pulls/" + prNumber);
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3.diff");
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + token);
        }

        StringBuilder diffOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diffOutput.append(line).append("\n");
            }
        }

        return parseDiff(diffOutput.toString());
    }

    /**
     * Bitbucket PR URL 에서 diff 분석
     */
    public StructuredDiff analyzeBitbucketPR(String prUrl, String username, String appPassword) throws IOException {
        // Extract workspace, repo, PR number from URL
        // Format: https://bitbucket.org/workspace/repo/pull-requests/123
        String[] parts = prUrl.split("/");
        if (parts.length < 7) {
            throw new IOException("Invalid Bitbucket PR URL format");
        }
        String workspace = parts[parts.length - 4];
        String repo = parts[parts.length - 3];
        String prNumber = parts[parts.length - 1];

        // Fetch diff from Bitbucket API
        java.net.URL url = new java.net.URL("https://api.bitbucket.org/2.0/repositories/" + workspace + "/" + repo + "/pullrequests/" + prNumber + "/diff");
        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        if (username != null && appPassword != null) {
            String auth = username + ":" + appPassword;
            String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);
        }

        StringBuilder diffOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                diffOutput.append(line).append("\n");
            }
        }

        return parseDiff(diffOutput.toString());
    }

    /**
     * Raw diff 텍스트를 파싱하여 구조화된 Diff 객체로 변환
     */
    public StructuredDiff parseDiff(String diffText) {
        StructuredDiff result = new StructuredDiff();
        String[] lines = diffText.split("\n");
        
        FileDiff currentFile = null;
        Hunk currentHunk = null;
        int lineNumberOld = 0;
        int lineNumberNew = 0;

        for (String line : lines) {
            // 파일 변경 감지
            Matcher fileMatcher = FILE_PATTERN.matcher(line);
            if (fileMatcher.matches()) {
                if (currentFile != null) {
                    result.addFile(currentFile);
                }
                currentFile = new FileDiff(fileMatcher.group(1), fileMatcher.group(2));
                continue;
            }

            // Hunk 시작 감지
            Matcher hunkMatcher = HUNK_PATTERN.matcher(line);
            if (hunkMatcher.matches() && currentFile != null) {
                if (currentHunk != null) {
                    currentFile.addHunk(currentHunk);
                }
                lineNumberOld = Integer.parseInt(hunkMatcher.group(1));
                lineNumberNew = Integer.parseInt(hunkMatcher.group(3));
                String context = hunkMatcher.group(5).trim();
                currentHunk = new Hunk(lineNumberOld, lineNumberNew, context);
                continue;
            }

            // 라인 추가/삭제 처리
            if (currentHunk != null) {
                if (ADDITION_PATTERN.matcher(line).find()) {
                    Change change = new Change(ChangeType.ADDITION, lineNumberNew, lineNumberOld, line.substring(1));
                    currentHunk.addChange(change);
                    lineNumberNew++;
                } else if (DELETION_PATTERN.matcher(line).find()) {
                    Change change = new Change(ChangeType.DELETION, lineNumberNew, lineNumberOld, line.substring(1));
                    currentHunk.addChange(change);
                    lineNumberOld++;
                } else if (!line.startsWith("\\") && !line.isEmpty()) {
                    // 컨텍스트 라인 (변경 없음)
                    Change change = new Change(ChangeType.CONTEXT, lineNumberNew, lineNumberOld, line);
                    currentHunk.addChange(change);
                    lineNumberOld++;
                    lineNumberNew++;
                }
            }
        }

        // 마지막 파일과 hunk 추가
        if (currentHunk != null && currentFile != null) {
            currentFile.addHunk(currentHunk);
        }
        if (currentFile != null) {
            result.addFile(currentFile);
        }

        return result;
    }

    /**
     * Diff 를 JSON 형식으로 직렬화
     */
    public String toJson(StructuredDiff diff) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"files\": [\n");
        
        List<FileDiff> files = diff.getFiles();
        for (int i = 0; i < files.size(); i++) {
            FileDiff file = files.get(i);
            json.append("    {\n");
            json.append("      \"oldPath\": \"").append(escapeJson(file.getOldPath())).append("\",\n");
            json.append("      \"newPath\": \"").append(escapeJson(file.getNewPath())).append("\",\n");
            json.append("      \"hunks\": [\n");
            
            List<Hunk> hunks = file.getHunks();
            for (int j = 0; j < hunks.size(); j++) {
                Hunk hunk = hunks.get(j);
                json.append("        {\n");
                json.append("          \"lineNumberOld\": ").append(hunk.getLineNumberOld()).append(",\n");
                json.append("          \"lineNumberNew\": ").append(hunk.getLineNumberNew()).append(",\n");
                json.append("          \"context\": \"").append(escapeJson(hunk.getContext())).append("\",\n");
                json.append("          \"changes\": [\n");
                
                List<Change> changes = hunk.getChanges();
                for (int k = 0; k < changes.size(); k++) {
                    Change change = changes.get(k);
                    json.append("            {\n");
                    json.append("              \"type\": \"").append(change.getType().name()).append("\",\n");
                    json.append("              \"lineNumberNew\": ").append(change.getLineNumberNew()).append(",\n");
                    json.append("              \"lineNumberOld\": ").append(change.getLineNumberOld()).append(",\n");
                    json.append("              \"content\": \"").append(escapeJson(change.getContent())).append("\"\n");
                    json.append("            }");
                    if (k < changes.size() - 1) json.append(",");
                    json.append("\n");
                }
                
                json.append("          ]\n");
                json.append("        }");
                if (j < hunks.size() - 1) json.append(",");
                json.append("\n");
            }
            
            json.append("      ]\n");
            json.append("    }");
            if (i < files.size() - 1) json.append(",");
            json.append("\n");
        }
        
        json.append("  ]\n");
        json.append("}\n");
        
        return json.toString();
    }

    private String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r")
                   .replace("\t", "\\t");
    }
}
