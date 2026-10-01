package com.yrootlab.onmaru.catalog.application.pagination;

import java.util.List;

public record AdminPage<T>(List<T> items, boolean hasNext) {
    public AdminPage {
        items = List.copyOf(items);
    }
}
