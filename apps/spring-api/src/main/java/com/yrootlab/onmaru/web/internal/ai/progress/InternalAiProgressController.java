package com.yrootlab.onmaru.web.internal.ai.progress;

import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/internal/v1/ai/runs")
public final class InternalAiProgressController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalAiProgressController.class);
    private static final String EXPECTED_SCHEMA_VERSION = "internal.ai.progress.v1";
    private static final Map<String, String> STAGE_MAPPING = Map.of(
            "PREPARING_INPUT", "INTERPRETING",
            "RETRIEVING", "RETRIEVING",
            "SELECTING_EVIDENCE", "RETRIEVING",
            "GENERATING", "VALIDATING",
            "CHECKING_OUTPUT", "VALIDATING"
    );

    private final InternalAiProgressVerifier verifier;
    private final JourneyRunEventSink eventSink;
    private final Map<UUID, Integer> lastSeenOrdinal = new ConcurrentHashMap<>();

    public InternalAiProgressController(
            InternalAiProgressVerifier verifier,
            JourneyRunEventSink eventSink) {
        this.verifier = verifier;
        this.eventSink = eventSink;
    }

    @PostMapping("/{runId}/progress")
    public ResponseEntity<Void> recordProgress(
            @PathVariable UUID runId,
            @RequestHeader(name = "X-Run-Progress-Token", required = false) String token,
            @RequestBody InternalAiProgressPayload payload) {

        if (payload == null || !EXPECTED_SCHEMA_VERSION.equals(payload.schemaVersion())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (!runId.equals(payload.runId())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        if (verifier != null && !verifier.verifyToken(token, runId, payload.planHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        // Deduplicate or ignore stale ordinals
        var prevOrdinal = lastSeenOrdinal.compute(runId, (k, oldVal) -> {
            if (oldVal == null || payload.ordinal() > oldVal) {
                return payload.ordinal();
            }
            return oldVal;
        });

        if (prevOrdinal != null && prevOrdinal > payload.ordinal()) {
            // Duplicate or stale ordinal, idempotent 200 OK
            return ResponseEntity.ok().build();
        }

        // Map AI stage to Spring Public SSE stage
        String publicStage = STAGE_MAPPING.getOrDefault(payload.stage(), "VALIDATING");

        try {
            eventSink.stage(runId, "RUNNING", publicStage);
        } catch (Exception ex) {
            LOGGER.warn("Failed to publish progress stage for run {}: {}", runId, ex.getMessage());
        }

        return ResponseEntity.ok().build();
    }
}
