package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.curation.AdminCuration;
import com.yrootlab.onmaru.admin.curation.AdminCurationStore;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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

    public AdminCurationController(AdminAuthenticator authenticator, AdminCurationStore store) {
        this.authenticator = authenticator;
        this.store = store;
    }

    @Operation(summary = "큐레이션 override 목록 조회")
    @GetMapping("/api/v1/admin/curations")
    public ResponseEntity<?> list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean included,
            @RequestParam(defaultValue = "20") int limit,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            if (limit < 1 || limit > 100 || (category != null && !List.of("VILLAGE", "STAY", "ROUTE").contains(category))) {
                throw new IllegalArgumentException();
            }
            return ok(Map.of("schemaVersion", "1.0", "items", store.find(category, included, limit), "hasNext", false));
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
            HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (body == null || !List.of("VILLAGE", "STAY", "ROUTE").contains(body.category()) || body.badges() == null) {
                throw new IllegalArgumentException();
            }
            var result = store.upsert(placeId, body.category(), body.included(), body.badges(), actor.id());
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
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

    private record CurationRequest(String category, boolean included, List<String> badges) {}
}
