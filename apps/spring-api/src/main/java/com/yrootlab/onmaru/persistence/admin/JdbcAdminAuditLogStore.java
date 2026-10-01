package com.yrootlab.onmaru.persistence.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.admin.audit.AdminAuditLog;
import com.yrootlab.onmaru.admin.audit.AdminAuditLogStore;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JdbcAdminAuditLogStore implements AdminAuditLogStore {
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public JdbcAdminAuditLogStore(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }

    @Override
    public void append(AdminAuditLog auditLog) {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                INSERT INTO onmaru.identity_admin_audit_log
                    (id, actor_admin_id, action, resource_type, resource_id, reason, note,
                     before_state, after_state, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """)) {
            statement.setObject(1, auditLog.id());
            statement.setObject(2, auditLog.actorAdminId());
            statement.setString(3, auditLog.action());
            statement.setString(4, auditLog.resourceType());
            statement.setString(5, auditLog.resourceId());
            statement.setString(6, auditLog.reason());
            statement.setString(7, auditLog.note());
            statement.setString(8, json(auditLog.beforeState()));
            statement.setString(9, json(auditLog.afterState()));
            statement.setObject(10, auditLog.requestId());
            statement.setObject(11, auditLog.createdAt());
            statement.executeUpdate();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to append admin audit log", exception);
        }
    }

    @Override
    public List<AdminAuditLog> findByActor(UUID actorAdminId, int limit) {
        validate(actorAdminId, limit);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT id, actor_admin_id, action, resource_type, resource_id, reason, note,
                       before_state, after_state, request_id, created_at
                FROM onmaru.identity_admin_audit_log
                WHERE actor_admin_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """)) {
            statement.setObject(1, actorAdminId);
            statement.setInt(2, limit);
            return readAll(statement);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load admin audit logs by actor", exception);
        }
    }

    @Override
    public List<AdminAuditLog> findByResource(String resourceType, String resourceId, int limit) {
        if (resourceType == null || resourceType.isBlank() || resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceType and resourceId are required");
        }
        validateLimit(limit);
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT id, actor_admin_id, action, resource_type, resource_id, reason, note,
                       before_state, after_state, request_id, created_at
                FROM onmaru.identity_admin_audit_log
                WHERE resource_type = ? AND resource_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """)) {
            statement.setString(1, resourceType.trim());
            statement.setString(2, resourceId);
            statement.setInt(3, limit);
            return readAll(statement);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to load admin audit logs by resource", exception);
        }
    }

    private List<AdminAuditLog> readAll(java.sql.PreparedStatement statement) throws SQLException {
        try (var result = statement.executeQuery()) {
            List<AdminAuditLog> entries = new ArrayList<>();
            while (result.next()) {
                entries.add(read(result));
            }
            return entries;
        }
    }

    private AdminAuditLog read(java.sql.ResultSet result) throws SQLException {
        try {
            return new AdminAuditLog(
                    result.getObject("id", UUID.class), result.getObject("actor_admin_id", UUID.class),
                    result.getString("action"), result.getString("resource_type"), result.getString("resource_id"),
                    result.getString("reason"), result.getString("note"),
                    readJson(result.getString("before_state")), readJson(result.getString("after_state")),
                    result.getObject("request_id", UUID.class),
                    result.getObject("created_at", OffsetDateTime.class).toInstant());
        } catch (Exception exception) {
            throw new SQLException("Invalid admin audit log row", exception);
        }
    }

    private Map<String, Object> readJson(String value) throws Exception {
        return value == null ? null : mapper.readValue(value, new TypeReference<>() {});
    }

    private String json(Map<String, Object> value) throws Exception {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    private static void validate(UUID actorAdminId, int limit) {
        if (actorAdminId == null) {
            throw new IllegalArgumentException("actorAdminId is required");
        }
        validateLimit(limit);
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
    }
}
