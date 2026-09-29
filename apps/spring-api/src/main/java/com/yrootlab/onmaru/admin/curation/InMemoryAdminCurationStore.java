package com.yrootlab.onmaru.admin.curation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class InMemoryAdminCurationStore implements AdminCurationStore {
    private final List<AdminCuration> items = new ArrayList<>();

    @Override
    public synchronized List<AdminCuration> find(String category, Boolean included, int limit) {
        return items.stream()
                .filter(item -> category == null || item.category().equals(category))
                .filter(item -> included == null || item.included() == included)
                .limit(limit)
                .toList();
    }

    @Override
    public synchronized AdminCuration upsert(UUID placeId, String category, boolean included, List<String> badges, UUID adminId) {
        var current = items.stream().filter(item -> item.canonicalPlaceId().equals(placeId) && item.category().equals(category)).findFirst();
        var next = new AdminCuration(UUID.randomUUID(), placeId, category, included, badges, null,
                current.map(item -> item.version() + 1).orElse(1L), adminId, Instant.now());
        current.ifPresent(items::remove);
        items.add(next);
        return next;
    }
}
