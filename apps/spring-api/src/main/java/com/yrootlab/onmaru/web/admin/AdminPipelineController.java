package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminAuthenticationException;
import com.yrootlab.onmaru.admin.pagination.AdminCursorCodec;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.admin.pipeline.AdminPipelinePort;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyInvalidException;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyMissingException;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@Tag(name = "AdminPipelines", description = "관리자 데이터 pipeline")
@RestController
public final class AdminPipelineController {
    private final AdminAuthenticator authenticator;
    private final AdminPipelinePort pipeline;
    private final AdminCursorCodec cursorCodec;

    public AdminPipelineController(
            AdminAuthenticator authenticator,
            AdminPipelinePort pipeline,
            AdminCursorCodec cursorCodec) {
        this.authenticator = authenticator;
        this.pipeline = pipeline;
        this.cursorCodec = cursorCodec;
    }

    @Operation(summary = "pipeline 상태 조회")
    @GetMapping("/api/v1/admin/pipelines/{dataset}/status")
    public ResponseEntity<?> status(@PathVariable String dataset, @RequestHeader(name = "Authorization", required = false) String authorization, HttpServletRequest request) {
        try { authenticator.authenticate(authorization); return ok(pipeline.status(dataset)); }
        catch (AdminAuthenticationException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
        catch (IllegalArgumentException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
        catch (IllegalStateException exception) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", request); }
        catch (RuntimeException exception) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request); }
    }

    @Operation(summary = "pipeline 실행 단건 조회")
    @GetMapping("/api/v1/admin/pipelines/{dataset}/runs/{runId}")
    public ResponseEntity<?> runDetails(@PathVariable String dataset, @PathVariable String runId,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try { authenticator.authenticate(authorization); return ok(pipeline.run(dataset, UUID.fromString(runId))); }
        catch (AdminAuthenticationException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
        catch (com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunNotFoundException exception) { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request); }
        catch (IllegalArgumentException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
        catch (IllegalStateException exception) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", request); }
        catch (RuntimeException exception) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request); }
    }

    @Operation(summary = "pipeline 실행 실패 로그 조회")
    @GetMapping("/api/v1/admin/pipelines/{dataset}/runs/{runId}/failures")
    public ResponseEntity<?> failures(@PathVariable String dataset, @PathVariable String runId,
            @RequestParam(defaultValue = "20") String limit, @RequestParam(required = false) String cursor,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            HttpServletRequest request) {
        try {
            authenticator.authenticate(authorization);
            int parsedLimit = Integer.parseInt(limit);
            if (parsedLimit < 1 || parsedLimit > 100 || cursor != null && cursor.length() > 512) throw new IllegalArgumentException();
            UUID parsedRunId = UUID.fromString(runId);
            String filter = "dataset=" + dataset + "&runId=" + parsedRunId;
            AdminCursor decoded = cursorCodec.decodeOptional(cursor, "pipeline-failures", parsedLimit, filter);
            var page = pipeline.failures(dataset, parsedRunId, parsedLimit, decoded);
            String nextCursor = null;
            if (page.hasNext() && !page.items().isEmpty()) {
                var last = page.items().get(page.items().size() - 1);
                nextCursor = cursorCodec.encode(new AdminCursor("pipeline-failures", parsedLimit, filter,
                        last.occurredAt(), last.id(), null, page.totalCount()));
            }
            return ok(new com.yrootlab.onmaru.admin.pipeline.AdminPipelineFailurePage(
                    page.items(), page.totalCount(), page.hasNext(), nextCursor));
        } catch (AdminAuthenticationException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
        catch (com.yrootlab.onmaru.admin.pipeline.AdminPipelineRunNotFoundException exception) { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", request); }
        catch (IllegalArgumentException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
        catch (IllegalStateException exception) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", request); }
        catch (RuntimeException exception) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request); }
    }

    @Operation(summary = "pipeline 실행 요청")
    @PostMapping("/api/v1/admin/pipelines/{dataset}/runs")
    public ResponseEntity<?> run(
            @PathVariable String dataset,
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @RequestBody(required = false) PipelineRunRequest body,
            HttpServletRequest request) {
        try {
            var actor = authenticator.authenticate(authorization);
            if (!actor.role().canManagePipelines()) return error(HttpStatus.FORBIDDEN, "FORBIDDEN", request);
            if (!"kto-korean-tour".equals(dataset) || body == null || body.scope() == null) {
                throw new IllegalArgumentException("dataset and scope are required");
            }
            String scope = body.scope();
            if (!"ALL".equals(scope)) throw new IllegalArgumentException("only ALL scope is supported");
            IdempotencyKey.fromHeader(idempotencyKey);
            throw new UnsupportedOperationException("TourAPI collection is owned by the scheduled pipeline");
        } catch (IdempotencyKeyMissingException | IdempotencyKeyInvalidException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
        catch (IllegalArgumentException exception) { return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", request); }
        catch (UnsupportedOperationException exception) { return error(HttpStatus.NOT_IMPLEMENTED, "NOT_IMPLEMENTED", request); }
        catch (AdminAuthenticationException exception) { return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", request); }
        catch (IllegalStateException exception) { return error(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", request); }
        catch (RuntimeException exception) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", request); }
    }

    private ResponseEntity<?> ok(Object body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    private ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        String requestId = attribute instanceof String text && !text.isBlank() ? text : UUID.randomUUID().toString();
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new ApiErrorResponse("1.2", code, code, requestId, Map.of()));
    }

    public record PipelineRunRequest(String scope) { }
}
