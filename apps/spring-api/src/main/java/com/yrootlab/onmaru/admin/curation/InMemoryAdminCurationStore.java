package com.yrootlab.onmaru.admin.curation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;
import java.util.Comparator;

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
    public synchronized AdminPage<AdminCuration> findPage(String category, Boolean included, int limit, AdminCursor cursor) {
        var filtered = items.stream()
                .filter(item -> category == null || item.category().equals(category))
                .filter(item -> included == null || item.included() == included)
                .sorted(Comparator.comparing(AdminCuration::updatedAt).reversed().thenComparing(AdminCuration::id, Comparator.reverseOrder()))
                .filter(item -> cursor == null || item.updatedAt().isBefore(cursor.timestamp())
                        || item.updatedAt().equals(cursor.timestamp()) && item.id().compareTo(cursor.id()) < 0)
                .limit(limit + 1L)
                .toList();
        boolean hasNext = filtered.size() > limit;
        return new AdminPage<>(filtered.subList(0, Math.min(limit, filtered.size())), hasNext);
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
