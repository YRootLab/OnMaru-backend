package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.users.AdminMember;
import com.yrootlab.onmaru.admin.users.AdminMemberStore;
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

    public AdminUserController(AdminAuthenticator authenticator, AdminMemberStore memberStore) {
        this.authenticator = authenticator;
        this.memberStore = memberStore;
    }

    @Operation(summary = "관리자 회원 목록 조회")
    @GetMapping("/api/v1/admin/users")
    public ResponseEntity<?> users(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100) {
                throw new IllegalArgumentException();
            }
            List<UserResponse> items = memberStore.find(status, limit).stream().map(UserResponse::from).toList();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "schemaVersion", "1.0", "items", items, "hasNext", false));
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
