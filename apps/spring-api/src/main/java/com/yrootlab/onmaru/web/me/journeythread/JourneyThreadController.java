package com.yrootlab.onmaru.web.me.journeythread;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunOutcome;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.journey.thread.JourneyThread;
import com.yrootlab.onmaru.journey.thread.JourneyThreadDetail;
import com.yrootlab.onmaru.journey.thread.JourneyThreadInputInvalidException;
import com.yrootlab.onmaru.journey.thread.JourneyThreadNotFoundException;
import com.yrootlab.onmaru.journey.thread.JourneyThreadService;
import com.yrootlab.onmaru.journey.thread.JourneyThreadSummary;
import com.yrootlab.onmaru.journey.thread.JourneyTurnMemory;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "04. AI 여정 탐색 (Journey & AI)", description = "AI 기반 여행 여정 탐색, 대화 턴, 실시간 SSE 이벤트 스트림, 코스 저장 API")
@RestController
public final class JourneyThreadController {

    private static final Logger LOGGER = LoggerFactory.getLogger(JourneyThreadController.class);
    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final JourneyThreadService journeyThreads;
    private final MemberLifecycleService members;
    private final IdempotencyService idempotency;

    public JourneyThreadController(
            JourneyThreadService journeyThreads,
            MemberLifecycleService members,
            IdempotencyService idempotency) {
        this.journeyThreads = journeyThreads;
        this.members = members;
        this.idempotency = idempotency;
    }

    @Operation(
            summary = "내 여정 탐색 대화 스레드 히스토리 목록 조회",
            description = "로그인한 회원이 진행했던 AI 여정 탐색 스레드 목록을 최신순으로 커서 페이징 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "스레드 목록 조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/me/journey-threads")
    public ResponseEntity<?> list(
            @Parameter(description = "조회 개수 (기본값 20)", example = "20")
            @RequestParam(defaultValue = "20") int limit,
            @Parameter(description = "다음 페이지 커서 토큰")
            @RequestParam(required = false) String cursor,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }

        try {
            var page = journeyThreads.list(member.get().id(), limit, cursor);
            LOGGER.atInfo()
                    .addKeyValue("member.id", member.get().id())
                    .addKeyValue("items.count", page.items().size())
                    .addKeyValue("has_more", page.hasMore())
                    .log("journey_thread_listed");

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(new PageResponse(
                            "1.2",
                            page.items().stream().map(this::toSummaryResponse).toList(),
                            page.nextCursor(),
                            page.hasMore()));
        } catch (JourneyThreadInputInvalidException exception) {
            return validation(request, exception.field());
        }
    }

    @Operation(
            summary = "여정 탐색 대화 스레드 상세 및 대화 턴 히스토리 조회",
            description = "스레드 ID를 기반으로 대화 질의 내용, AI 생성 상태, 추천 결과, 턴별 변경 이력을 상세 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "스레드 상세 조회 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "스레드를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/me/journey-threads/{threadId}")
    public ResponseEntity<?> get(
            @Parameter(description = "스레드 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID threadId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }

        try {
            var detail = journeyThreads.get(member.get().id(), threadId);
            LOGGER.atInfo()
                    .addKeyValue("member.id", member.get().id())
                    .addKeyValue("thread.id", threadId)
                    .log("journey_thread_viewed");

            return ResponseEntity.ok()
                    .cacheControl(CacheControl.noStore())
                    .body(toDetailResponse(detail));
        } catch (JourneyThreadNotFoundException exception) {
            return notFound(request);
        } catch (JourneyThreadInputInvalidException exception) {
            return validation(request, exception.field());
        }
    }

    @Operation(
            summary = "여정 탐색 대화 스레드 삭제",
            description = "대화 스레드 기록을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "스레드 삭제 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "스레드를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/me/journey-threads/{threadId}")
    public ResponseEntity<?> delete(
            @Parameter(description = "스레드 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID threadId,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }

        try {
            var key = IdempotencyKey.fromHeader(idempotencyKey);
            var path = "/api/v1/me/journey-threads/" + threadId;
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(),
                    "MEMBER:" + member.get().id(),
                    "DELETE",
                    path,
                    IdempotencyFingerprint.sha256("DELETE", path, "journey-thread.delete", threadId)), () -> {
                journeyThreads.delete(member.get().id(), threadId);
                LOGGER.atInfo()
                        .addKeyValue("member.id", member.get().id())
                        .addKeyValue("thread.id", threadId)
                        .log("journey_thread_deleted");
                return new com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse(204, Map.of(), null);
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).build();
        } catch (JourneyThreadNotFoundException exception) {
            return notFound(request);
        } catch (JourneyThreadInputInvalidException exception) {
            return validation(request, exception.field());
        }
    }

    private SummaryResponse toSummaryResponse(JourneyThreadSummary summary) {
        return new SummaryResponse(
                summary.threadId(),
                summary.explorationId(),
                summary.title(),
                summary.lastUserQueryPreview(),
                summary.lastOutcome() != null ? summary.lastOutcome().name() : null,
                summary.latestRunStatus() != null ? summary.latestRunStatus().name() : null,
                summary.savedJourneyId(),
                summary.candidateCount(),
                summary.pinnedCount(),
                summary.updatedAt());
    }

    private DetailResponse toDetailResponse(JourneyThreadDetail detail) {
        var thread = detail.thread();
        return new DetailResponse(
                "1.2",
                thread.threadId(),
                thread.explorationId(),
                thread.title(),
                thread.lastUserQueryPreview(),
                thread.lastOutcome() != null ? thread.lastOutcome().name() : null,
                thread.latestRunStatus() != null ? thread.latestRunStatus().name() : null,
                thread.savedJourneyId(),
                thread.pinnedCount(),
                thread.candidateCount(),
                thread.createdAt(),
                thread.updatedAt(),
                detail.turnHistory().stream().map(this::toTurnResponse).toList(),
                detail.explorationSnapshotUrl());
    }

    private TurnResponse toTurnResponse(JourneyTurnMemory turn) {
        return new TurnResponse(
                turn.turnId(),
                turn.actorType(),
                turn.redactedQuery(),
                turn.redactionFlags(),
                turn.runId(),
                turn.outcome() != null ? turn.outcome().name() : null,
                turn.createdAt());
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication is required.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "여정 탐색 기록을 찾을 수 없습니다.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> validation(HttpServletRequest request, String field) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", request, Map.of("field", field == null ? "query" : field));
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request,
            Map<String, Object> details) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, message, requestId(request), details));
    }

    private String requestId(HttpServletRequest request) {
        var value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String id && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    public record SummaryResponse(
            UUID threadId,
            UUID explorationId,
            String title,
            String lastUserQueryPreview,
            String lastOutcome,
            String latestRunStatus,
            UUID savedJourneyId,
            int candidateCount,
            int pinnedCount,
            Instant updatedAt) {
    }

    public record PageResponse(
            String schemaVersion,
            List<SummaryResponse> items,
            String nextCursor,
            boolean hasMore) {
    }

    public record DetailResponse(
            String schemaVersion,
            UUID threadId,
            UUID explorationId,
            String title,
            String lastUserQueryPreview,
            String lastOutcome,
            String latestRunStatus,
            UUID savedJourneyId,
            int pinnedCount,
            int candidateCount,
            Instant createdAt,
            Instant updatedAt,
            List<TurnResponse> turnHistory,
            String snapshotUrl) {
    }

    public record TurnResponse(
            UUID turnId,
            String actorType,
            String redactedQuery,
            List<String> redactionFlags,
            UUID runId,
            String outcome,
            Instant createdAt) {
    }
}
