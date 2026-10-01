package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.audit.AdminAuditLogService;
import com.yrootlab.onmaru.admin.curation.AdminCuration;
import com.yrootlab.onmaru.admin.curation.AdminCurationStore;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.admin.pagination.AdminCursorCodec;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyInvalidException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyMissingException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminCurations", description = "관리자 큐레이션 override")
@RestController
public final class AdminCurationController {
    private final AdminAuthenticator authenticator;
    private final AdminCurationStore store;
    private final AdminAuditLogService auditLogs;
    private final IdempotencyService idempotency;
    private final AdminCursorCodec cursorCodec;

    public AdminCurationController(
            AdminAuthenticator authenticator,
            AdminCurationStore store,
            AdminAuditLogService auditLogs,
            IdempotencyService idempotency,
            AdminCursorCodec cursorCodec) {
        this.authenticator = authenticator;
        this.store = store;
        this.auditLogs = auditLogs;
        this.idempotency = idempotency;
        this.cursorCodec = cursorCodec;
    }

    @Operation(summary = "큐레이션 override 목록 조회")
    @GetMapping("/api/v1/admin/curations")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean included,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100 || (category != null && !List.of("VILLAGE", "STAY", "ROUTE").contains(category))) {
                throw new IllegalArgumentException();
            }
            if (cursor != null && cursor.length() > 512) throw new IllegalArgumentException();
            String normalizedCategory = category == null ? "" : category;
            String filter = "category=" + normalizedCategory + "&included=" + (included == null ? "" : included);
            AdminCursor decoded = cursorCodec.decodeOptional(cursor, "curations", limit, filter);
            var page = store.findPage(category, included, limit, decoded);
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("schemaVersion", "1.0");
            response.put("items", page.items());
            response.put("hasNext", page.hasNext());
            if (page.hasNext() && !page.items().isEmpty()) {
                AdminCuration last = page.items().getLast();
                response.put("nextCursor", cursorCodec.encode(new AdminCursor(
                        "curations", limit, filter, last.updatedAt(), last.id())));
            }
            return ok(response);
        } catch (IdempotencyKeyMissingException | IdempotencyKeyInvalidException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    @Operation(summary = "큐레이션 override 저장")
    @PutMapping("/api/v1/admin/curations/{placeId}")
    public ResponseEntity<?> upsert(
            @PathVariable UUID placeId,
            @RequestBody CurationRequest body,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (body == null || !List.of("VILLAGE", "STAY", "ROUTE").contains(body.category()) || body.badges() == null) {
                throw new IllegalArgumentException();
            }
            var key = IdempotencyKey.fromHeader(idempotencyKey);
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(), actor.id().toString(), "PUT", "/api/v1/admin/curations/" + placeId,
                    IdempotencyFingerprint.sha256("PUT", placeId.toString(), "admin.curation.upsert", body)), () -> {
                var result = store.upsert(placeId, body.category(), body.included(), body.badges(), actor.id());
                auditLogs.append(actor, "CURATION_UPDATED", "place_curation", placeId.toString(),
                        null, null, Map.of(), Map.of(
                                "category", body.category(), "included", body.included(), "badges", body.badges()), requestId(request));
                return IdempotentResponse.ok(result);
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }

    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank() ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }

    private UUID requestId(HttpServletRequest request) {
        Object value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        try {
            return value == null ? null : UUID.fromString(value.toString());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private record CurationRequest(String category, boolean included, List<String> badges) {}
}
