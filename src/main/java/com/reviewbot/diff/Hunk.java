package com.reviewbot.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Diff Hunk (연속된 변경 블록)
 */
public class Hunk {
    private final int lineNumberOld;
    private final int lineNumberNew;
    private final String context;
    private final List<Change> changes = new ArrayList<>();

    public Hunk(int lineNumberOld, int lineNumberNew, String context) {
        this.lineNumberOld = lineNumberOld;
        this.lineNumberNew = lineNumberNew;
        this.context = context;
    }

    public void addChange(Change change) {
        this.changes.add(change);
    }

    public int getLineNumberOld() {
        return lineNumberOld;
    }

    public int getLineNumberNew() {
        return lineNumberNew;
    }

    public String getContext() {
        return context;
    }

    public List<Change> getChanges() {
        return changes;
    }

    public int getChangeCount() {
        return changes.size();
    }
}
