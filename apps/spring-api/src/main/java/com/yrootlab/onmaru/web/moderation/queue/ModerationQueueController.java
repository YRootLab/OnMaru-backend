package com.yrootlab.onmaru.web.moderation.queue;

import com.yrootlab.onmaru.community.moderation.ModerationQueueItem;
import com.yrootlab.onmaru.community.moderation.ModerationQueueService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "03. 지도 & 방문 후기 (Map & Reviews)", description = "방문 후기 작성, 조회, 좋아요, 신고, 행정구역별 지도 통계 API")
@RestController
final class ModerationQueueController {

    private final OperatorAuthenticator authenticator;
    private final ModerationQueueService queueService;

    ModerationQueueController(OperatorAuthenticator authenticator, ModerationQueueService queueService) {
        this.authenticator = authenticator;
        this.queueService = queueService;
    }

    @Operation(
            summary = "운영자 검수 대기열 큐 조회",
            description = "운영자가 검수해야 할 신고 목록 및 대기열(Queue) 스냅샷을 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "검수 대기열 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 limit 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "운영자 인증 실패", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/operations/moderation/queue")
    ResponseEntity<?> queue(
            @Parameter(description = "운영자 인증 헤더 (Bearer 토큰)")
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @Parameter(description = "운영자 식별자", example = "operator-admin")
            @RequestHeader(name = "X-OnMaru-Operator", required = false) String actorRef,
            @Parameter(description = "조회 건수 (기본값 100)", example = "100")
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request) {
        authenticator.authenticate(authorization, actorRef);
        try {
            var snapshot = queueService.snapshot(limit);
            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new ModerationQueueResponse(
                            "1.2",
                            snapshot.generatedAt(),
                            snapshot.oldestOpenReportAgeSeconds(),
                            snapshot.items()));
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .cacheControl(CacheControl.noStore())
                    .body(ApiErrorResponse.of(
                            ApiErrorCode.VALIDATION_ERROR,
                            requestId(request),
                            Map.of("field", "limit")));
        }
    }

    private String requestId(HttpServletRequest request) {
        Object attribute = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        if (attribute instanceof String requestId && !requestId.isBlank()) {
            return requestId;
        }
        String header = request.getHeader(RequestIdFilter.HEADER);
        return header == null || header.isBlank() ? UUID.randomUUID().toString() : header;
    }

    private record ModerationQueueResponse(
            String schemaVersion,
            Instant generatedAt,
            long oldestOpenReportAgeSeconds,
            List<ModerationQueueItem> items) {

        private ModerationQueueResponse {
            items = List.copyOf(items);
        }
    }
}
