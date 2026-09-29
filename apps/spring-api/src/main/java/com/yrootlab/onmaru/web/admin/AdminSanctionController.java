package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.users.AdminSanction;
import com.yrootlab.onmaru.admin.users.AdminSanctionService;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminUsers", description = "관리자 회원 제재")
@RestController
public final class AdminSanctionController {

    private final AdminAuthenticator authenticator;
    private final AdminSanctionService sanctionService;

    public AdminSanctionController(AdminAuthenticator authenticator, AdminSanctionService sanctionService) {
        this.authenticator = authenticator;
        this.sanctionService = sanctionService;
    }

    @Operation(summary = "회원 제재 이력 조회")
    @GetMapping("/api/v1/admin/users/{memberId}/sanctions")
    public ResponseEntity<?> list(
            @PathVariable UUID memberId,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of(
                    "schemaVersion", "1.0", "items", sanctionService.find(memberId)));
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    @Operation(summary = "회원 제재 생성")
    @PostMapping("/api/v1/admin/users/{memberId}/sanctions")
    public ResponseEntity<?> create(
            @PathVariable UUID memberId,
            @RequestBody SanctionRequest body,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (body == null) {
                throw new IllegalArgumentException();
            }
            var sanction = sanctionService.create(actor, memberId, body.reason(), body.startsAt(), body.endsAt());
            return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(sanction);
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN", request);
        } catch (IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request);
        } catch (RuntimeException exception) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request);
        }
    }

    @Operation(summary = "회원 제재 해제")
    @DeleteMapping("/api/v1/admin/users/{memberId}/sanctions/{sanctionId}")
    public ResponseEntity<?> revoke(
            @PathVariable UUID memberId,
            @PathVariable UUID sanctionId,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (!sanctionService.revoke(actor, sanctionId)) {
                return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request);
            }
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        } catch (SecurityException exception) {
            return error(HttpStatus.FORBIDDEN, "FORBIDDEN", request);
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

    private record SanctionRequest(String reason, Instant startsAt, Instant endsAt) {
    }
}
