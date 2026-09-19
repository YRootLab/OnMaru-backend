package com.yrootlab.onmaru.tourism.catalog.client;

public record TourApiParseResult(
        TourApiOutcomeKind kind,
        TourApiPage page,
        TourApiError error
) {
    public static TourApiParseResult success(TourApiPage page) {
        return new TourApiParseResult(TourApiOutcomeKind.SUCCESS, page, null);
    }

    public static TourApiParseResult error(TourApiError error) {
        return new TourApiParseResult(error.kind(), null, error);
    }
}
