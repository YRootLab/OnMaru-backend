package com.yrootlab.onmaru.stamp;

import java.util.List;

public record StampBook(StampBookSummary summary, List<StampBookItem> items) {
    public StampBook {
        items = List.copyOf(items);
    }
}
