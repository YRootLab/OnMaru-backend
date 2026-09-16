package com.yrootlab.onmaru.audio.query;

import java.util.List;

public record OdiiStoryPage(
        String schemaVersion,
        OdiiCoverageStatus coverageStatus,
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiStorySummary> items,
        String nextCursor,
        boolean hasMore) {

    public OdiiStoryPage {
        items = List.copyOf(items);
    }
}
