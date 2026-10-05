package com.yrootlab.onmaru.web.presence;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.web.admission.ClientIdentityResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Tag(name = "05. 실시간 온기 (Live Presence)", description = "한옥 룸 실시간 접속자 수 및 온기 파티클 SSE 스트림")
@RestController
final class PresenceStreamController {

    private static final int CLIENT_ID_MAX_LENGTH = 64;

    private final PresenceRoomRegistry registry;
    private final PresenceProperties properties;
    private final AdmissionService admissionService;
    private final AdmissionPolicy admissionPolicy;
    private final ClientIdentityResolver clientIdentityResolver;

    PresenceStreamController(
            PresenceRoomRegistry registry,
            PresenceProperties properties,
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            ClientIdentityResolver clientIdentityResolver) {
        this.registry = registry;
        this.properties = properties;
        this.admissionService = admissionService;
        this.admissionPolicy = admissionPolicy;
        this.clientIdentityResolver = clientIdentityResolver;
    }

    @Operation(summary = "룸 실시간 존재 SSE 스트림",
            description = "연결 즉시 snapshot을 수신하고, 인원 변화·온기 반응을 실시간으로 push합니다. 25초마다 ping 코멘트가 전송됩니다.")
    @GetMapping(path = "/api/v1/realtime/presence/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> stream(
            @RequestParam String roomId,
            @RequestParam String clientId,
            HttpServletRequest request) {

        if (!properties.isAllowed(roomId)) {
            return ResponseEntity.badRequest().build();
        }
        if (clientId == null || clientId.isBlank() || clientId.length() > CLIENT_ID_MAX_LENGTH) {
            return ResponseEntity.badRequest().build();
        }

        var ip = clientIdentityResolver.clientIp(request);
        var streamRequest = new AdmissionRequest("presence.stream", new AdmissionSubject(SubjectType.IP, ip));
        var ipDecision = admissionService.admitActive(streamRequest, admissionPolicy);
        if (!ipDecision.allowed()) {
            return ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(Math.max(1, ipDecision.retryAfter().toSeconds())))
                    .build();
        }

        // 0L = no framework-managed timeout; lifecycle managed via ping TTL
        var emitter = new SseEmitter(0L);

        var snapshotJson = registry.join(roomId, clientId, emitter, properties.maxConnections());
        if (snapshotJson == null) {
            admissionService.releaseActive(streamRequest, admissionPolicy);
            return ResponseEntity.status(503).build();
        }

        try {
            emitter.send(SseEmitter.event().name("snapshot").data(snapshotJson));
        } catch (IOException e) {
            emitter.completeWithError(e);
            registry.leave(roomId, clientId, emitter);
            admissionService.releaseActive(streamRequest, admissionPolicy);
            return sse(emitter);
        }

        Runnable cleanup = () -> {
            registry.leave(roomId, clientId, emitter);
            admissionService.releaseActive(streamRequest, admissionPolicy);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(ignored -> cleanup.run());

        return sse(emitter);
    }

    private ResponseEntity<SseEmitter> sse(SseEmitter emitter) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "event-stream", StandardCharsets.UTF_8))
                .header("Cache-Control", "no-cache, no-transform")
                .header("X-Accel-Buffering", "no")
                .body(emitter);
    }
}
