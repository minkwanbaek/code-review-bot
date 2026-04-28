package com.reviewbot.review;

/**
 * 컨벤션 위반 항목
 */
public class Violation {
    private final Severity severity;
    private final String rule;
    private final String message;
    private int lineNumber;

    public Violation(Severity severity, String rule, String message) {
        this.severity = severity;
        this.rule = rule;
        this.message = message;
    }

    public Violation(Severity severity, String rule, String message, int lineNumber) {
        this.severity = severity;
        this.rule = rule;
        this.message = message;
        this.lineNumber = lineNumber;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getRule() {
        return rule;
    }

    public String getMessage() {
        return message;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public void setLineNumber(int lineNumber) {
        this.lineNumber = lineNumber;
    }

    @Override
    public String toString() {
        return String.format("[%s] %s: %s", severity, rule, message);
    }
}
