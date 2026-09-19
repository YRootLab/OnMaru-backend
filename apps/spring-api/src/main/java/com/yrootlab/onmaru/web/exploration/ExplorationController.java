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
import com.yrootlab.onmaru.journey.actions.ActionType;
import com.yrootlab.onmaru.journey.actions.JourneyAction;
import com.yrootlab.onmaru.journey.actions.ProposalAction;
import com.yrootlab.onmaru.journey.actions.ResourceAction;
import com.yrootlab.onmaru.journey.actions.ResourceRef;
import com.yrootlab.onmaru.journey.cancellation.CancelJourneyRunCommand;
import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;
import com.yrootlab.onmaru.journey.run.RunNotFoundException;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.ObjectProvider;
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

@Tag(name = "04. AI 여정 탐색 (Journey & AI)", description = "AI 기반 여행 여정 탐색, 대화 턴, 실시간 SSE 이벤트 스트림, 코스 저장 API")
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
    private final ObjectProvider<JourneyRunCancellationService> cancellationService;

    ExplorationController(
            ExplorationService explorationService,
            ExplorationActorResolver actorResolver,
            IdempotencyService idempotencyService,
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            ExplorationSnapshotHydrator snapshotHydrator,
            ObjectProvider<JourneyRunCancellationService> cancellationService) {
        this.explorationService = explorationService;
        this.actorResolver = actorResolver;
        this.idempotencyService = idempotencyService;
        this.admissionService = admissionService;
        this.admissionPolicy = admissionPolicy;
        this.snapshotHydrator = snapshotHydrator;
        this.cancellationService = cancellationService;
    }

    @Operation(
            summary = "AI 여정 탐색 세션 생성 (비동기 런 접수)",
            description = "여행 질의(query), 지역 코드(regionCode)를 전달하여 AI 여정 탐색 세션을 생성하고 비동기 생성 런(Run)을 시작합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "여정 생성 런 접수 완료 (SSE 또는 폴링으로 상태 확인)"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청 본문", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "동시 실행 정원 초과 (Admission Throttled)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/explorations")
    ResponseEntity<RunAcceptedResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "여정 탐색 시작 요청 DTO")
            @RequestBody(required = false) CreateRequest body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
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

    @Operation(
            summary = "AI 여정 탐색 상태 및 스냅샷 조회",
            description = "탐색 세션 ID를 기반으로 현재 확정된 코스, 제안 목록, 장소 상세 하이드레이션 데이터, 액션 상태를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "여정 탐색 스냅샷 조회 성공"),
            @ApiResponse(responseCode = "404", description = "탐색 세션을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/explorations/{explorationId}")
    ResponseEntity<ExplorationResponse> get(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        var snapshot = explorationService.get(actor, explorationId);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExplorationResponse.from(snapshot, snapshotHydrator.hydrate(snapshot), explorationService.actionState(actor, explorationId)));
    }

    @Operation(
            summary = "여정 수정 액션 적용 (PIN, EXCLUDE, PROPOSAL)",
            description = "코스 내 장소 고정(PIN), 제외(EXCLUDE), AI 추천 제안 수락/거절(APPLY_PROPOSAL, DISMISS_PROPOSAL) 액션을 적용합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "액션 적용 성공 및 갱신된 스냅샷 반환"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 액션 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "베이스 버전 불일치 (CAS 낙관적 락 충돌)", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/explorations/{explorationId}/actions")
    ResponseEntity<ExplorationResponse> applyAction(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "여정 수정 액션 요청 DTO")
            @RequestBody(required = false) ActionRequest body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        if (body == null || body.commandId() == null || body.baseVersion() == null || body.action() == null) {
            throw new ExplorationInputInvalidException("body");
        }
        rejectUnknownFields(body);
        rejectUnknownFields(body.action());
        rejectUnknownFields(body.action().resourceRef());
        var action = body.action().toDomain();
        var path = "/api/v1/explorations/" + explorationId + "/actions";
        var response = idempotencyService.execute(new IdempotencyCommand(key.value(), actor.type() + ":" + actor.subject(), "POST", path,
                IdempotencyFingerprint.sha256("POST", path, "exploration.action", java.util.List.of(body.commandId(), body.baseVersion(), action))), () -> {
            explorationService.applyAction(actor, explorationId, body.baseVersion(), action);
            var snapshot = explorationService.get(actor, explorationId);
            return IdempotentResponse.ok(ExplorationResponse.from(snapshot, snapshotHydrator.hydrate(snapshot), explorationService.actionState(actor, explorationId)));
        });
        return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body((ExplorationResponse) response.body());
    }

    @Operation(
            summary = "AI 실행 런(Run) 단건 상태 조회",
            description = "비동기로 실행 중인 AI 추천 런(Run)의 현재 진행 상태(QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED)를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "런 상태 조회 성공"),
            @ApiResponse(responseCode = "404", description = "런을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/explorations/{explorationId}/runs/{runId}")
    ResponseEntity<ExplorationResponse.RunResponse> getRun(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @Parameter(description = "AI 실행 런 UUID", example = "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e6f")
            @PathVariable UUID runId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExplorationResponse.RunResponse.from(explorationService.getRun(actor, explorationId, runId)));
    }

    @Operation(
            summary = "AI 대화 턴 추가 (추가 질의/피드백)",
            description = "기존 여정 탐색 세션에 사용자의 추가 요구사항이나 명확화 응답(clarificationAnswer)을 전달하여 새로운 AI 런을 실행합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "대화 턴 런 접수 완료"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 턴 요청 본문", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "429", description = "동시 실행 정원 초과", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/explorations/{explorationId}/turns")
    ResponseEntity<RunAcceptedResponse> createTurn(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "대화 턴 추가 요청 DTO")
            @RequestBody(required = false) CreateTurnRequest body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
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

    @Operation(
            summary = "진행 중인 AI 실행 런(Run) 취소",
            description = "대기열(QUEUED) 또는 실행 중(RUNNING)인 AI 추천 생성을 즉시 취소합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 실행 취소 완료"),
            @ApiResponse(responseCode = "404", description = "런 또는 탐색 세션을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/explorations/{explorationId}/runs/{runId}/cancel")
    ResponseEntity<ExplorationResponse.RunResponse> cancelRun(
            @Parameter(description = "탐색 세션 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID explorationId,
            @Parameter(description = "AI 실행 런 UUID", example = "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e6f")
            @PathVariable UUID runId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "빈 JSON 객체")
            @RequestBody(required = false) Map<String, Object> body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @Parameter(description = "게스트 토큰 쿠키", hidden = true)
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
            cancelDurableRunIfPresent(actor, runId, path);
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

    private void cancelDurableRunIfPresent(ExplorationActor actor, UUID runId, String path) {
        var service = cancellationService.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.cancel(new CancelJourneyRunCommand(
                    UUID.randomUUID(),
                    actorKey(actor),
                    runId,
                    IdempotencyFingerprint.sha256("POST", path, "run.cancel.durable", runId),
                    java.time.Instant.now()));
        } catch (RunNotFoundException ignored) {
            LOGGER.atDebug()
                    .addKeyValue("run.id", runId)
                    .log("journey_durable_run_cancel_skipped_missing_row");
        }
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

    private String actorKey(ExplorationActor actor) {
        return actor.type().name() + ":" + actor.subject();
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

    static final class ActionRequest implements StrictRequest {
        private final UUID commandId;
        private final Integer baseVersion;
        private final ActionBody action;
        private final Set<String> unknownFields = new LinkedHashSet<>();

        @JsonCreator
        ActionRequest(@JsonProperty("commandId") UUID commandId, @JsonProperty("baseVersion") Integer baseVersion,
                @JsonProperty("action") ActionBody action) {
            this.commandId = commandId;
            this.baseVersion = baseVersion;
            this.action = action;
        }
        UUID commandId() { return commandId; }
        Integer baseVersion() { return baseVersion; }
        ActionBody action() { return action; }
        public Set<String> unknownFields() { return Set.copyOf(unknownFields); }
        @JsonAnySetter void unknown(String name, Object ignored) { unknownFields.add(name); }
    }

    static final class ActionBody implements StrictRequest {
        private final String type;
        private final ResourceBody resourceRef;
        private final UUID proposalId;
        private final Set<String> unknownFields = new LinkedHashSet<>();

        @JsonCreator
        ActionBody(@JsonProperty("type") String type, @JsonProperty("resourceRef") ResourceBody resourceRef,
                @JsonProperty("proposalId") UUID proposalId) {
            this.type = type;
            this.resourceRef = resourceRef;
            this.proposalId = proposalId;
        }
        JourneyAction toDomain() {
            try {
                var actionType = ActionType.valueOf(type);
                return switch (actionType) {
                    case PIN, UNPIN, EXCLUDE, UNEXCLUDE -> new ResourceAction(actionType, resourceRef.toDomain());
                    case APPLY_PROPOSAL, DISMISS_PROPOSAL -> new ProposalAction(actionType, proposalId);
                };
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new ExplorationInputInvalidException("action");
            }
        }
        ResourceBody resourceRef() { return resourceRef; }
        public Set<String> unknownFields() { return Set.copyOf(unknownFields); }
        @JsonAnySetter void unknown(String name, Object ignored) { unknownFields.add(name); }
    }

    static final class ResourceBody implements StrictRequest {
        private final String type;
        private final String id;
        private final Set<String> unknownFields = new LinkedHashSet<>();
        @JsonCreator ResourceBody(@JsonProperty("type") String type, @JsonProperty("id") String id) { this.type = type; this.id = id; }
        ResourceRef toDomain() { return new ResourceRef(type, id); }
        public Set<String> unknownFields() { return Set.copyOf(unknownFields); }
        @JsonAnySetter void unknown(String name, Object ignored) { unknownFields.add(name); }
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
