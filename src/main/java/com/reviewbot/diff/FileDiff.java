package com.reviewbot.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * 파일별 Diff 정보
 */
public class FileDiff {
    private final String oldPath;
    private final String newPath;
    private final List<Hunk> hunks = new ArrayList<>();

    public FileDiff(String oldPath, String newPath) {
        this.oldPath = oldPath;
        this.newPath = newPath;
    }

    public void addHunk(Hunk hunk) {
        this.hunks.add(hunk);
    }

    public String getOldPath() {
        return oldPath;
    }

    public String getNewPath() {
        return newPath;
    }

    public List<Hunk> getHunks() {
        return hunks;
    }

    public int getHunkCount() {
        return hunks.size();
    }
}
