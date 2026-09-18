package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.events.JourneyRunEvent;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.UUID;

@Tag(name = "04. AI 여정 탐색 (Journey & AI)", description = "AI 기반 여행 여정 탐색, 대화 턴, 실시간 SSE 이벤트 스트림, 코스 저장 API")
@RestController
final class ExplorationRunEventsController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final ExplorationService explorationService;
    private final ExplorationActorResolver actorResolver;
    private final InMemoryJourneyRunEventStream eventStream;
    private final ApplicationEventPublisher eventPublisher;

    ExplorationRunEventsController(
            ExplorationService explorationService,
            ExplorationActorResolver actorResolver,
            InMemoryJourneyRunEventStream eventStream,
            ApplicationEventPublisher eventPublisher) {
        this.explorationService = explorationService;
        this.actorResolver = actorResolver;
        this.eventStream = eventStream;
        this.eventPublisher = eventPublisher;
    }

    @Operation(
            summary = "AI 여정 생성 실시간 SSE 이벤트 스트림",
            description = "AI 여정 생성 과정(토큰 스트리밍, 코스 구성, 제안 생성, 상태 전이)을 Server-Sent Events(text/event-stream)로 실시간 수신합니다. Last-Event-ID 헤더를 통한 재연결 및 유실 이벤트 리플레이를 지원합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "SSE 스트림 연결 수립 (text/event-stream)"),
            @ApiResponse(responseCode = "404", description = "런 또는 탐색 세션을 찾을 수 없음")
    })
    @GetMapping(path = "/api/v1/explorations/{explorationId}/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @Parameter(description = "AI 실행 런 UUID", example = "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e6f")
            @PathVariable UUID runId,
            @Parameter(description = "마지막 수신 이벤트 ID (SSE 재연결 시)", example = "42")
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = resolveActor(sessionToken, guestToken);
        var emitter = new SseEmitter(60_000L);
        if (actor == null) {
            eventPublisher.publishEvent(JourneySseTelemetryEvent.authClosed());
            send(emitter, eventStream.authClosed());
            emitter.complete();
            return sse(emitter);
        }
        var snapshot = explorationService.get(actor.actor(), explorationId);
        if (!snapshot.run().id().equals(runId)) {
            throw new com.yrootlab.onmaru.journey.exploration.ExplorationNotFoundException();
        }
        var parsedLastEventId = parseLastEventId(lastEventId);
        var opened = eventStream.open(runId, parsedLastEventId, event -> {
            send(emitter, event);
            if (event.closeAfterSend()) {
                emitter.complete();
            }
        });
        eventPublisher.publishEvent(JourneySseTelemetryEvent.replayed(runId, parsedLastEventId, opened.replay()));
        if (opened.replay().events().stream().anyMatch(JourneyRunEvent::closeAfterSend)) {
            close(opened.subscription());
            return sse(emitter);
        }
        subscribeToFutureEvents(runId, emitter, opened.subscription());
        return sse(emitter);
    }

    private ExplorationActorResolver.ResolvedExplorationActor resolveActor(String sessionToken, String guestToken) {
        try {
            return actorResolver.resolve(sessionToken, guestToken);
        } catch (ExplorationAuthenticationRequiredException exception) {
            return null;
        }
    }

    private Long parseLastEventId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private ResponseEntity<SseEmitter> sse(SseEmitter emitter) {
        var contentType = new MediaType("text", "event-stream", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CacheControl.noStore())
                .body(emitter);
    }

    private void subscribeToFutureEvents(UUID runId, SseEmitter emitter, AutoCloseable subscription) {
        AutoCloseable heartbeat = eventStream.scheduleHeartbeat(runId, event -> send(emitter, event));
        Runnable closeAll = () -> {
            close(subscription);
            close(heartbeat);
        };
        emitter.onCompletion(closeAll);
        emitter.onTimeout(closeAll);
        emitter.onError(ignored -> closeAll.run());
    }

    private void send(SseEmitter emitter, JourneyRunEvent event) {
        try {
            var builder = SseEmitter.event()
                    .name(event.type().wireName())
                    .data(event.data());
            if (event.id() > 0) {
                builder.id(Long.toString(event.id()));
            }
            if (event.retry() != null) {
                builder.reconnectTime(event.retry().toMillis());
            }
            emitter.send(builder);
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
        }
    }

    private void close(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
