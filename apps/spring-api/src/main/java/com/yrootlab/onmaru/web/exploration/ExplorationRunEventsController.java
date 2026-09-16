package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.events.JourneyRunEvent;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
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

    @GetMapping(path = "/api/v1/explorations/{explorationId}/runs/{runId}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> events(
            @PathVariable UUID explorationId,
            @PathVariable UUID runId,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
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
