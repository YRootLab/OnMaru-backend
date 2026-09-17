package com.yrootlab.onmaru.journey.savedjourney;

import java.util.List;

public record SavedJourneyPage(List<SavedJourneySummary> items, String nextCursor, boolean hasMore) {
    public SavedJourneyPage {
        items = List.copyOf(items);
    }
}
