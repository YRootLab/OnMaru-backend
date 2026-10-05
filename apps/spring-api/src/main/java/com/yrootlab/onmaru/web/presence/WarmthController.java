package com.yrootlab.onmaru.web.presence;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;

@Tag(name = "05. 실시간 온기 (Live Presence)", description = "한옥 룸 실시간 접속자 수 및 온기 파티클 SSE 스트림")
@RestController
final class WarmthController {

    private static final String ALLOWED_TYPE = "firefly";
    private static final int CLIENT_ID_MAX_LENGTH = 64;

    private final PresenceRoomRegistry registry;
    private final PresenceProperties properties;
    private final AdmissionService admissionService;
    private final AdmissionPolicy admissionPolicy;
    private final Clock clock;

    WarmthController(
            PresenceRoomRegistry registry,
            PresenceProperties properties,
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            Clock clock) {
        this.registry = registry;
        this.properties = properties;
        this.admissionService = admissionService;
        this.admissionPolicy = admissionPolicy;
        this.clock = clock;
    }

    @Operation(summary = "온기 반응 전송",
            description = "같은 룸의 다른 SSE 구독자에게 warmth 이벤트를 브로드캐스트합니다. 응답은 fire-and-forget이므로 프론트는 응답을 기다리지 않아도 됩니다.")
    @PostMapping("/api/v1/realtime/warmth")
    ResponseEntity<Void> warmth(@RequestBody WarmthRequest body) {
        if (!properties.isAllowed(body.roomId())) {
            return ResponseEntity.badRequest().build();
        }
        if (body.clientId() == null || body.clientId().isBlank()
                || body.clientId().length() > CLIENT_ID_MAX_LENGTH) {
            return ResponseEntity.badRequest().build();
        }
        if (!ALLOWED_TYPE.equals(body.type())) {
            return ResponseEntity.badRequest().build();
        }
        if (body.x() == null || body.x() < 0.0 || body.x() > 1.0) {
            return ResponseEntity.badRequest().build();
        }

        var clientDecision = admissionService.admit(
                new AdmissionRequest("presence.warmth", new AdmissionSubject(SubjectType.CLIENT_ID, body.clientId())),
                admissionPolicy);
        if (!clientDecision.allowed()) {
            long retryAfterSeconds = Math.max(1, clientDecision.retryAfter().toSeconds());
            return ResponseEntity.status(429)
                    .header("Retry-After", Long.toString(retryAfterSeconds))
                    .build();
        }

        // roomId 기준 초당 50회 상한 초과 시 조용히 드롭
        var roomDecision = admissionService.admit(
                new AdmissionRequest("presence.warmth.room", new AdmissionSubject(SubjectType.ROOM_ID, body.roomId())),
                admissionPolicy);
        if (!roomDecision.allowed()) {
            return ResponseEntity.noContent().build();
        }

        registry.broadcastWarmth(
                body.roomId(),
                body.clientId(),
                new PresenceWarmthEvent(body.type(), body.x(), clock.instant().getEpochSecond()));

        return ResponseEntity.noContent().build();
    }
}
