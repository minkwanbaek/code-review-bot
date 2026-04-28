package com.reviewbot.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * 구조화된 Diff 정보
 */
public class StructuredDiff {
    private final List<FileDiff> files = new ArrayList<>();

    public void addFile(FileDiff file) {
        this.files.add(file);
    }

    public List<FileDiff> getFiles() {
        return files;
    }

    public int getFileCount() {
        return files.size();
    }

    public int getTotalChangeCount() {
        return files.stream()
                .flatMap(f -> f.getHunks().stream())
                .flatMap(h -> h.getChanges().stream())
                .filter(c -> c.getType() != ChangeType.CONTEXT)
                .mapToInt(c -> 1)
                .sum();
    }
}
