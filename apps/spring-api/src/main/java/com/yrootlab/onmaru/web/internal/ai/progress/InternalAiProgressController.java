package com.yrootlab.onmaru.web.internal.ai.progress;

import com.yrootlab.onmaru.integration.ai.progress.AiRunProgressStore;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
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

/**
 * Accepts AI stage callbacks for a bound run. Only a durable, first-seen ordinal on an active run updates
 * the projection and publishes SSE; duplicates, stale ordinals, and terminal runs are acknowledged without effect.
 */
@RestController
@RequestMapping("/internal/v1/ai/runs")
public final class InternalAiProgressController {

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalAiProgressController.class);
    static final String EXPECTED_SCHEMA_VERSION = "internal.ai.progress.v1";
    private static final Map<String, JourneyRunStage> STAGE_MAPPING = Map.of(
            "PREPARING_INPUT", JourneyRunStage.INTERPRETING,
            "RETRIEVING", JourneyRunStage.RETRIEVING,
            "SELECTING_EVIDENCE", JourneyRunStage.SELECTING_EVIDENCE,
            "GENERATING", JourneyRunStage.GENERATING,
            "CHECKING_OUTPUT", JourneyRunStage.VALIDATING
    );

    private final InternalAiProgressVerifier verifier;
    private final AiRunProgressStore progress;
    private final JourneyRunEventSink eventSink;

    public InternalAiProgressController(
            InternalAiProgressVerifier verifier,
            AiRunProgressStore progress,
            JourneyRunEventSink eventSink) {
        this.verifier = verifier;
        this.progress = progress;
        this.eventSink = eventSink;
    }

    @PostMapping("/{runId}/progress")
    public ResponseEntity<Void> recordProgress(
            @PathVariable UUID runId,
            @RequestHeader(name = "X-Run-Progress-Token", required = false) String token,
            @RequestBody InternalAiProgressPayload payload) {

        if (payload == null || !EXPECTED_SCHEMA_VERSION.equals(payload.schemaVersion())
                || !runId.equals(payload.runId()) || payload.ordinal() < 1) {
            return status(HttpStatus.BAD_REQUEST);
        }
        JourneyRunStage stage = payload.stage() == null ? null : STAGE_MAPPING.get(payload.stage());
        if (stage == null) {
            return status(HttpStatus.BAD_REQUEST);
        }

        var boundPlan = progress.boundPlanHash(runId);
        if (boundPlan.isEmpty()) {
            return status(HttpStatus.NOT_FOUND);
        }
        if (!boundPlan.get().equals(payload.planHash())) {
            return status(HttpStatus.CONFLICT);
        }
        if (!verifier.verifyToken(token, runId, boundPlan.get())) {
            return status(HttpStatus.UNAUTHORIZED);
        }

        var result = progress.record(runId, payload.planHash(), payload.ordinal(), stage);
        return switch (result.decision()) {
            case APPLIED -> {
                if (result.publish() != null) {
                    publish(runId, result.publish());
                }
                yield ResponseEntity.ok().build();
            }
            case DUPLICATE, STALE, INACTIVE -> ResponseEntity.ok().build();
            case CONFLICT, PLAN_MISMATCH -> status(HttpStatus.CONFLICT);
            case NOT_BOUND -> status(HttpStatus.NOT_FOUND);
        };
    }

    private void publish(UUID runId, JourneyRunStage stage) {
        try {
            eventSink.stage(runId, "RUNNING", stage.name());
        } catch (RuntimeException exception) {
            // The projection is already committed; SSE clients recover from the snapshot on reconnect.
            LOGGER.warn("Failed to publish progress stage for run {}", runId, exception);
        }
    }

    private static ResponseEntity<Void> status(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }
}
