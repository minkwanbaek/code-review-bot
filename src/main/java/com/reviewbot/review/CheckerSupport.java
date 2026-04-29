package com.reviewbot.review;

import com.reviewbot.diff.Change;
import com.reviewbot.diff.ChangeType;
import com.reviewbot.diff.FileDiff;
import com.reviewbot.diff.Hunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class CheckerSupport {
    private CheckerSupport() {
    }

    static List<AddedLine> addedLines(FileDiff file) {
        List<AddedLine> lines = new ArrayList<>();
        for (Hunk hunk : file.getHunks()) {
            for (Change change : hunk.getChanges()) {
                if (change.getType() == ChangeType.ADDITION) {
                    lines.add(new AddedLine(change.getLineNumberNew(), change.getContent()));
                }
            }
        }
        return lines;
    }

    static Severity severity(String severity, Severity fallback) {
        if (severity == null || severity.isBlank()) {
            return fallback;
        }
        try {
            return Severity.valueOf(severity.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    static boolean isJavaFile(FileDiff file) {
        return file.getNewPath() != null && file.getNewPath().endsWith(".java");
    }

    record AddedLine(int lineNumber, String content) {
    }
}
