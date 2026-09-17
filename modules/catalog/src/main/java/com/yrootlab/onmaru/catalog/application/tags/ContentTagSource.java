package com.yrootlab.onmaru.catalog.application.tags;

import java.util.List;

public record ContentTagSource(
        String title,
        String category,
        String body,
        List<String> highlights) {

    public ContentTagSource {
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
    }

    public static ContentTagSource of(
            String title,
            String category,
            String body,
            List<String> highlights) {
        return new ContentTagSource(title, category, body, highlights);
    }
}
