package com.yrootlab.onmaru.admin.audit;

import com.yrootlab.onmaru.admin.auth.AdminPrincipal;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AdminAuditLogService {
    private final AdminAuditLogStore store;
    private final Clock clock;

    public AdminAuditLogService(AdminAuditLogStore store, Clock clock) {
        if (store == null || clock == null) {
            throw new IllegalArgumentException("store and clock are required");
        }
        this.store = store;
        this.clock = clock;
    }

    public AdminAuditLog append(
            AdminPrincipal actor,
            String action,
            String resourceType,
            String resourceId,
            String reason,
            String note,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            UUID requestId) {
        if (actor == null) {
            throw new SecurityException("admin actor is required");
        }
        var auditLog = new AdminAuditLog(
                UUID.randomUUID(), actor.id(), action, resourceType, resourceId, reason, note,
                beforeState, afterState, requestId, Instant.now(clock));
        store.append(auditLog);
        return auditLog;
    }

    public List<AdminAuditLog> findByActor(UUID actorAdminId, int limit) {
        return store.findByActor(actorAdminId, limit);
    }

    public List<AdminAuditLog> findByResource(String resourceType, String resourceId, int limit) {
        return store.findByResource(resourceType, resourceId, limit);
    }
}
