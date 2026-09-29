package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminPipelines", description = "관리자 데이터 pipeline")
@RestController
public final class AdminPipelineController {
    private final AdminAuthenticator authenticator;
    private final AdminPipelinePort pipeline;

    public AdminPipelineController(AdminAuthenticator authenticator, AdminPipelinePort pipeline) {
        this.authenticator = authenticator;
        this.pipeline = pipeline;
    }

    @Operation(summary = "pipeline 상태 조회")
    @GetMapping("/api/v1/admin/pipelines/{dataset}/status")
    public ResponseEntity<?> status(@PathVariable String dataset, @RequestHeader(name = "Authorization", required = false) String authorization, HttpServletRequest request) {
        try { authenticator.authenticate(authorization); return ok(pipeline.status(dataset)); }
        catch (RuntimeException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
    }

    @Operation(summary = "pipeline 실행 요청")
    @PostMapping("/api/v1/admin/pipelines/{dataset}/runs")
    public ResponseEntity<?> run(@PathVariable String dataset, @RequestHeader(name = "Authorization", required = false) String authorization, HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (!actor.role().canManagePipelines()) return error(HttpStatus.FORBIDDEN, "FORBIDDEN", request);
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(pipeline.run(dataset));
        } catch (UnsupportedOperationException exception) { return error(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", request); }
        catch (RuntimeException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
    }

    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank() ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }
}
