package com.yrootlab.onmaru.admin.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** An immutable, append-only record of an administrator action. */
public record AdminAuditLog(
        UUID id,
        UUID actorAdminId,
        String action,
        String resourceType,
        String resourceId,
        String reason,
        String note,
        Map<String, Object> beforeState,
        Map<String, Object> afterState,
        UUID requestId,
        Instant createdAt) {

    public AdminAuditLog {
        if (id == null || actorAdminId == null || action == null || action.isBlank()
                || action.trim().length() > 80 || resourceType == null || resourceType.isBlank()
                || resourceType.trim().length() > 80 || (resourceId != null && resourceId.length() > 160)
                || (reason != null && reason.length() > 300) || (note != null && note.length() > 1000)
                || createdAt == null) {
            throw new IllegalArgumentException("admin audit log is invalid");
        }
        action = action.trim();
        resourceType = resourceType.trim();
        resourceId = normalize(resourceId);
        reason = normalize(reason);
        note = normalize(note);
        beforeState = immutableObject(beforeState);
        afterState = immutableObject(afterState);
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().isEmpty() ? null : value.trim();
    }

    private static Map<String, Object> immutableObject(Map<String, Object> value) {
        return value == null ? null : Map.copyOf(value);
    }
}
