package com.reviewbot.diff;

/**
 * 개별 코드 변경 라인
 */
public class Change {
    private final ChangeType type;
    private final int lineNumberNew;
    private final int lineNumberOld;
    private final String content;

    public Change(ChangeType type, int lineNumberNew, int lineNumberOld, String content) {
        this.type = type;
        this.lineNumberNew = lineNumberNew;
        this.lineNumberOld = lineNumberOld;
        this.content = content;
    }

    public ChangeType getType() {
        return type;
    }

    public int getLineNumberNew() {
        return lineNumberNew;
    }

    public int getLineNumberOld() {
        return lineNumberOld;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return String.format("[%s] L%d: %s", type, lineNumberNew, content);
    }
}
