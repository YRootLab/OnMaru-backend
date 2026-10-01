package com.yrootlab.onmaru.admin.audit;

import java.util.List;
import java.util.UUID;

public interface AdminAuditLogStore {
    void append(AdminAuditLog auditLog);

    List<AdminAuditLog> findByActor(UUID actorAdminId, int limit);

    List<AdminAuditLog> findByResource(String resourceType, String resourceId, int limit);
}
