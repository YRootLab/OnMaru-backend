package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;

public record HanokListPage(
        String schemaVersion,
        List<HanokCard> items,
        String nextCursor,
        boolean hasMore) {
}
