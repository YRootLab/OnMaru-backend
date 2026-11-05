package com.yrootlab.onmaru.journey.exploration;

import java.util.List;

public record ExplorationTurnPage(
        String schemaVersion,
        List<ExplorationTurn> items,
        String nextCursor,
        boolean hasMore) {

    public ExplorationTurnPage(List<ExplorationTurn> items, String nextCursor, boolean hasMore) {
        this("1.3", items, nextCursor, hasMore);
    }
}
