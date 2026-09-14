package com.yrootlab.onmaru.tourism.catalog.client;

import java.util.List;

public record TourApiPage(
        String operation,
        int pageNo,
        int numOfRows,
        int totalCount,
        boolean isLastPage,
        List<TourApiSourceRecord> items
) {
}
