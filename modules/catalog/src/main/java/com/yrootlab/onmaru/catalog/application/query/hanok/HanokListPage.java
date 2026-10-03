package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;

public record HanokListPage(
        String schemaVersion,
        List<HanokCard> items,
        long totalCount,
        String nextCursor,
        boolean hasMore) {
}
