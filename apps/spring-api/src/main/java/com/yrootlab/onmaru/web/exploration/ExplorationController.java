package com.yrootlab.onmaru.web.exploration;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationCommand;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationTurnCommand;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.journey.exploration.ExplorationInputInvalidException;
import com.yrootlab.onmaru.journey.exploration.ExplorationInputRejectedException;
import com.yrootlab.onmaru.journey.exploration.ExplorationNotFoundException;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.journey.exploration.ExplorationSnapshot;
import com.yrootlab.onmaru.journey.exploration.ExplorationRunStatus;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public final class ExplorationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExplorationController.class);
    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final String JOURNEY_AI_OPERATION = "journey.ai";

    private final ExplorationService explorationService;
    private final ExplorationActorResolver actorResolver;
    private final IdempotencyService idempotencyService;
    private final AdmissionService admissionService;
    private final AdmissionPolicy admissionPolicy;
    private final ExplorationSnapshotHydrator snapshotHydrator;

    ExplorationController(
            ExplorationService explorationService,
            ExplorationActorResolver actorResolver,
            IdempotencyService idempotencyService,
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            ExplorationSnapshotHydrator snapshotHydrator) {
        this.explorationService = explorationService;
        this.actorResolver = actorResolver;
        this.idempotencyService = idempotencyService;
        this.admissionService = admissionService;
        this.admissionPolicy = admissionPolicy;
        this.snapshotHydrator = snapshotHydrator;
    }

    @PostMapping("/api/v1/explorations")
    ResponseEntity<RunAcceptedResponse> create(
            @RequestBody(required = false) CreateRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var resolved = actorResolver.resolve(sessionToken, guestToken);
        rejectUnknownFields(body);
        var command = body == null
                ? new CreateExplorationCommand(null, null, null)
                : new CreateExplorationCommand(body.query(), body.locale(), body.regionCode());
        var response = idempotencyService.execute(new IdempotencyCommand(
                key.value(),
                resolved.actor().type() + ":" + resolved.actor().subject(),
                "POST",
                "/api/v1/explorations",
                IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "exploration.create", command)), () -> {
            var admitted = false;
            if (explorationService.willCreateAiRun(resolved.actor(), command)) {
                admitAiRun(resolved.actor());
                admitted = true;
            }
            try {
                var snapshot = explorationService.create(resolved.actor(), command);
                actorResolver.linkExploration(resolved, snapshot.explorationId());
                return IdempotentResponse.accepted(RunAcceptedResponse.from(snapshot));
            } catch (RuntimeException exception) {
                if (admitted) {
                    releaseAiRun(resolved.actor());
                }
                throw exception;
            }
        });
        return runAccepted(response);
    }

    @GetMapping("/api/v1/explorations/{explorationId}")
    ResponseEntity<ExplorationResponse> get(
            @PathVariable UUID explorationId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        var snapshot = explorationService.get(actor, explorationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExplorationResponse.from(snapshot, snapshotHydrator.hydrate(snapshot)));
    }

    @GetMapping("/api/v1/explorations/{explorationId}/runs/{runId}")
    ResponseEntity<ExplorationResponse.RunResponse> getRun(
            @PathVariable UUID explorationId,
            @PathVariable UUID runId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExplorationResponse.RunResponse.from(explorationService.getRun(actor, explorationId, runId)));
    }

    @PostMapping("/api/v1/explorations/{explorationId}/turns")
    ResponseEntity<RunAcceptedResponse> createTurn(
            @PathVariable UUID explorationId,
            @RequestBody(required = false) CreateTurnRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        if (body == null || body.baseVersion() == null) {
            throw new ExplorationInputInvalidException(body == null ? "body" : "baseVersion");
        }
        rejectUnknownFields(body);
        rejectUnknownFields(body.clarificationAnswer());
        var command = new CreateExplorationTurnCommand(
                body.clientTurnId(),
                body.baseVersion(),
                body.query(),
                regionCode(body.clarificationAnswer()),
                body.clarificationAnswer() == null ? null : body.clarificationAnswer().clarificationId());
        var path = "/api/v1/explorations/" + explorationId + "/turns";
        var response = idempotencyService.execute(new IdempotencyCommand(
                key.value(),
                actor.type() + ":" + actor.subject(),
                "POST",
                path,
                IdempotencyFingerprint.sha256("POST", path, "exploration.turn", command)), () -> {
            var admitted = false;
            if (explorationService.willCreateTurnAiRun(actor, explorationId, command)) {
                admitAiRun(actor);
                admitted = true;
            }
            try {
                var snapshot = explorationService.createTurn(actor, explorationId, command);
                return IdempotentResponse.accepted(RunAcceptedResponse.from(snapshot));
            } catch (RuntimeException exception) {
                if (admitted) {
                    releaseAiRun(actor);
                }
                throw exception;
            }
        });
        return runAccepted(response);
    }

    @PostMapping("/api/v1/explorations/{explorationId}/runs/{runId}/cancel")
    ResponseEntity<ExplorationResponse.RunResponse> cancelRun(
            @PathVariable UUID explorationId,
            @PathVariable UUID runId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        if (body != null && !body.isEmpty()) {
            throw new ExplorationInputInvalidException("body");
        }
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        var before = explorationService.get(actor, explorationId);
        if (!before.run().id().equals(runId)) {
            throw new ExplorationNotFoundException();
        }
        var path = "/api/v1/explorations/" + explorationId + "/runs/" + runId + "/cancel";
        var response = idempotencyService.execute(new IdempotencyCommand(
                key.value(),
                actor.type() + ":" + actor.subject(),
                "POST",
                path,
                IdempotencyFingerprint.sha256("POST", path, "run.cancel", runId)), () -> {
            var cancelled = explorationService.cancelRun(explorationId, runId);
            if (isActive(before)) {
                releaseAiRun(actor);
            }
            return IdempotentResponse.ok(ExplorationResponse.RunResponse.from(new ExplorationSnapshot(
                    explorationId,
                    before.stateVersion(),
                    before.regionCode(),
                    cancelled,
                    cancelled.createdAt())));
        });
        return run(response);
    }

    private void admitAiRun(ExplorationActor actor) {
        var decision = admissionService.admitActive(new AdmissionRequest(
                JOURNEY_AI_OPERATION,
                new AdmissionSubject(subjectType(actor.type()), actor.subject())
        ), admissionPolicy);
        LOGGER.atInfo()
                .addKeyValue("operation", JOURNEY_AI_OPERATION)
                .addKeyValue("actorType", actor.type().name())
                .addKeyValue("allowed", decision.allowed())
                .addKeyValue("retryAfterMs", decision.retryAfter().toMillis())
                .log("journey_ai_admission_decision");
        if (!decision.allowed()) {
            throw new ExplorationQuotaExceededException(decision.retryAfter());
        }
    }

    private void releaseAiRun(ExplorationActor actor) {
        admissionService.releaseActive(new AdmissionRequest(
                JOURNEY_AI_OPERATION,
                new AdmissionSubject(subjectType(actor.type()), actor.subject())
        ), admissionPolicy);
    }

    private boolean isActive(ExplorationSnapshot snapshot) {
        return snapshot.run().status() == ExplorationRunStatus.QUEUED
                || snapshot.run().status() == ExplorationRunStatus.RUNNING;
    }

    private SubjectType subjectType(ExplorationActorType actorType) {
        return actorType == ExplorationActorType.MEMBER ? SubjectType.MEMBER : SubjectType.GUEST;
    }

    private String regionCode(ClarificationAnswer answer) {
        if (answer == null) {
            return null;
        }
        var hasChoice = answer.choiceId() != null && !answer.choiceId().isBlank();
        var hasText = answer.text() != null && !answer.text().isBlank();
        if (hasChoice == hasText || hasChoice) {
            throw new ExplorationInputInvalidException("clarificationAnswer");
        }
        var text = answer.text().trim();
        if (text.contains("전주")) {
            return "kr-45-jeonju";
        }
        if (text.contains("서울")) {
            return "kr-11-seoul";
        }
        if (text.contains("경주")) {
            return "kr-47-gyeongju";
        }
        if (text.contains("안동")) {
            return "kr-47-andong";
        }
        if (text.contains("부산")) {
            return "kr-26-busan";
        }
        return null;
    }

    private ResponseEntity<RunAcceptedResponse> runAccepted(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore())
                .body((RunAcceptedResponse) response.body());
    }

    private ResponseEntity<ExplorationResponse.RunResponse> run(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore())
                .body((ExplorationResponse.RunResponse) response.body());
    }

    private void rejectUnknownFields(StrictRequest body) {
        if (body != null && !body.unknownFields().isEmpty()) {
            throw new ExplorationInputRejectedException("VALIDATION_ERROR");
        }
    }

    private interface StrictRequest {
        Set<String> unknownFields();
    }

    static final class CreateRequest implements StrictRequest {
        private final String query;
        private final String locale;
        private final String regionCode;
        private final Set<String> unknownFields = new LinkedHashSet<>();

        @JsonCreator
        CreateRequest(
                @JsonProperty("query") String query,
                @JsonProperty("locale") String locale,
                @JsonProperty("regionCode") String regionCode) {
            this.query = query;
            this.locale = locale;
            this.regionCode = regionCode;
        }

        String query() {
            return query;
        }

        String locale() {
            return locale;
        }

        String regionCode() {
            return regionCode;
        }

        public Set<String> unknownFields() {
            return Set.copyOf(unknownFields);
        }

        @JsonAnySetter
        void unknown(String name, Object ignored) {
            unknownFields.add(name);
        }
    }

    static final class CreateTurnRequest implements StrictRequest {
        private final UUID clientTurnId;
        private final Integer baseVersion;
        private final String query;
        private final ClarificationAnswer clarificationAnswer;
        private final Set<String> unknownFields = new LinkedHashSet<>();

        @JsonCreator
        CreateTurnRequest(
                @JsonProperty("clientTurnId") UUID clientTurnId,
                @JsonProperty("baseVersion") Integer baseVersion,
                @JsonProperty("query") String query,
                @JsonProperty("clarificationAnswer") ClarificationAnswer clarificationAnswer) {
            this.clientTurnId = clientTurnId;
            this.baseVersion = baseVersion;
            this.query = query;
            this.clarificationAnswer = clarificationAnswer;
        }

        UUID clientTurnId() {
            return clientTurnId;
        }

        Integer baseVersion() {
            return baseVersion;
        }

        String query() {
            return query;
        }

        ClarificationAnswer clarificationAnswer() {
            return clarificationAnswer;
        }

        public Set<String> unknownFields() {
            return Set.copyOf(unknownFields);
        }

        @JsonAnySetter
        void unknown(String name, Object ignored) {
            unknownFields.add(name);
        }
    }

    static final class ClarificationAnswer implements StrictRequest {
        private final String clarificationId;
        private final String choiceId;
        private final String text;
        private final Set<String> unknownFields = new LinkedHashSet<>();

        @JsonCreator
        ClarificationAnswer(
                @JsonProperty("clarificationId") String clarificationId,
                @JsonProperty("choiceId") String choiceId,
                @JsonProperty("text") String text) {
            this.clarificationId = clarificationId;
            this.choiceId = choiceId;
            this.text = text;
        }

        String clarificationId() {
            return clarificationId;
        }

        String choiceId() {
            return choiceId;
        }

        String text() {
            return text;
        }

        public Set<String> unknownFields() {
            return Set.copyOf(unknownFields);
        }

        @JsonAnySetter
        void unknown(String name, Object ignored) {
            unknownFields.add(name);
        }
    }
}
