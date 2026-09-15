package com.yrootlab.onmaru.operations.moderation.queue;

import com.yrootlab.onmaru.community.moderation.ModerationQueueItem;
import com.yrootlab.onmaru.community.moderation.ModerationQueueService;
import com.yrootlab.onmaru.web.common.error.ApiErrorCode;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
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

@RestController
final class ModerationQueueController {

    private final OperatorAuthenticator authenticator;
    private final ModerationQueueService queueService;

    ModerationQueueController(OperatorAuthenticator authenticator, ModerationQueueService queueService) {
        this.authenticator = authenticator;
        this.queueService = queueService;
    }

    @GetMapping("/api/v1/operations/moderation/queue")
    ResponseEntity<?> queue(
            @RequestHeader(name = "Authorization", required = false) String authorization,
            @RequestHeader(name = "X-OnMaru-Operator", required = false) String actorRef,
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
