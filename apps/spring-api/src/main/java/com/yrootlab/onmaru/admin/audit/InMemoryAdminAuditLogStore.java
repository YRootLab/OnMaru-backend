package com.yrootlab.onmaru.admin.audit;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InMemoryAdminAuditLogStore implements AdminAuditLogStore {
    private final CopyOnWriteArrayList<AdminAuditLog> entries = new CopyOnWriteArrayList<>();

    @Override
    public void append(AdminAuditLog auditLog) {
        if (auditLog == null) {
            throw new IllegalArgumentException("auditLog is required");
        }
        entries.add(auditLog);
    }

    @Override
    public List<AdminAuditLog> findByActor(UUID actorAdminId, int limit) {
        requireQuery(actorAdminId, limit);
        return entries.stream()
                .filter(entry -> entry.actorAdminId().equals(actorAdminId))
                .sorted(newestFirst())
                .limit(limit)
                .toList();
    }

    @Override
    public List<AdminAuditLog> findByResource(String resourceType, String resourceId, int limit) {
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceType and resourceId are required");
        }
        requireLimit(limit);
        return entries.stream()
                .filter(entry -> entry.resourceType().equals(resourceType.trim())
                        && resourceId.equals(entry.resourceId()))
                .sorted(newestFirst())
                .limit(limit)
                .toList();
    }

    private static Comparator<AdminAuditLog> newestFirst() {
        return Comparator.comparing(AdminAuditLog::createdAt).reversed()
                .thenComparing(AdminAuditLog::id, Comparator.reverseOrder());
    }

    private static void requireQuery(UUID actorAdminId, int limit) {
        if (actorAdminId == null) {
            throw new IllegalArgumentException("actorAdminId is required");
        }
        requireLimit(limit);
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
