package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.users.AdminMember;
import com.yrootlab.onmaru.admin.users.AdminMemberStore;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.admin.pagination.AdminCursorCodec;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminUsers", description = "관리자 회원 조회")
@RestController
public final class AdminUserController {

    private final AdminAuthenticator authenticator;
    private final AdminMemberStore memberStore;
    private final AdminCursorCodec cursorCodec;

    public AdminUserController(AdminAuthenticator authenticator, AdminMemberStore memberStore, AdminCursorCodec cursorCodec) {
        this.authenticator = authenticator;
        this.memberStore = memberStore;
        this.cursorCodec = cursorCodec;
    }

    @Operation(summary = "관리자 회원 목록 조회")
    @GetMapping("/api/v1/admin/users")
    public ResponseEntity<?> users(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException();
            }
            if (cursor != null && cursor.length() > 512) throw new IllegalArgumentException();
            String normalizedStatus = status == null || status.isBlank() ? "" : status.trim().toUpperCase();
            if (!normalizedStatus.isEmpty() && !List.of("ACTIVE", "DELETING").contains(normalizedStatus)) {
                throw new IllegalArgumentException();
            }
            AdminCursor decoded = cursorCodec.decodeOptional(cursor, "users", limit, "status=" + normalizedStatus);
            var page = memberStore.findPage(normalizedStatus.isEmpty() ? null : normalizedStatus, limit, decoded);
            List<UserResponse> items = page.items().stream().map(UserResponse::from).toList();
            String nextCursor = page.hasNext() && !page.items().isEmpty()
                    ? cursorCodec.encode(new AdminCursor("users", limit, "status=" + normalizedStatus,
                            page.items().getLast().createdAt(), page.items().getLast().id(), null, page.totalCount()))
                    : null;
            Map<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("schemaVersion", "1.0");
            response.put("items", items);
            response.put("totalCount", page.totalCount());
            response.put("hasNext", page.hasNext());
            if (nextCursor != null) response.put("nextCursor", nextCursor);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response);
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

    private record UserResponse(UUID id, String status, java.time.Instant createdAt, long reviewCount) {
        static UserResponse from(AdminMember member) {
            return new UserResponse(member.id(), member.status(), member.createdAt(), member.reviewCount());
        }
    }
}
