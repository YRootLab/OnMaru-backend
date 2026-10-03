package com.yrootlab.onmaru.catalog.application.pagination;

import java.util.List;

public record AdminPage<T>(List<T> items, boolean hasNext, long totalCount) {
    public AdminPage {
        items = List.copyOf(items);
        if (totalCount < 0) {
            throw new IllegalArgumentException("totalCount must not be negative");
        }
    }
}
