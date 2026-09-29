package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.audit.AdminAuditLogService;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminAuditLogs", description = "관리자 감사 로그")
@RestController
public final class AdminAuditLogController {
    private final AdminAuthenticator authenticator;
    private final AdminAuditLogService auditLogs;

    public AdminAuditLogController(AdminAuthenticator authenticator, AdminAuditLogService auditLogs) {
        this.authenticator = authenticator;
        this.auditLogs = auditLogs;
    }

    @Operation(summary = "관리자 감사 로그 조회")
    @GetMapping("/api/v1/admin/audit-logs")
    public ResponseEntity<?> list(
            @RequestParam(required = false) UUID actorAdminId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 200 || (resourceType == null) != (resourceId == null)) {
                throw new IllegalArgumentException();
            }
            var items = resourceType != null
                    ? auditLogs.findByResource(resourceType, resourceId, limit)
                    : auditLogs.findByActor(actorAdminId, limit);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                    Map.of("schemaVersion", "1.0", "items", items, "hasMore", items.size() == limit));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank()
                ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }
}
