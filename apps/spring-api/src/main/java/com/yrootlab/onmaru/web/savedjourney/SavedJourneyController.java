package com.yrootlab.onmaru.web.savedjourney;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.actions.ResourceRef;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationActiveRunException;
import com.yrootlab.onmaru.journey.savedjourney.CreateSavedJourneyCommand;
import com.yrootlab.onmaru.journey.savedjourney.ResumeSavedJourneyResult;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourney;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyInputInvalidException;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyLimitExceededException;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyNotFoundException;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyService;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySnapshot;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySummary;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Tag(name = "04. AI 여정 탐색 (Journey & AI)", description = "AI 기반 여행 여정 탐색, 대화 턴, 실시간 SSE 이벤트 스트림, 코스 저장 API")
@RestController
public final class SavedJourneyController {

    private static final Logger LOGGER = LoggerFactory.getLogger(SavedJourneyController.class);
    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final SavedJourneyService savedJourneys;
    private final MemberLifecycleService members;
    private final IdempotencyService idempotency;

    SavedJourneyController(
            SavedJourneyService savedJourneys,
            MemberLifecycleService members,
            IdempotencyService idempotency) {
        this.savedJourneys = savedJourneys;
        this.members = members;
        this.idempotency = idempotency;
    }

    @Operation(
            summary = "완성된 AI 여정 코스 내 보관함에 저장",
            description = "AI 탐색으로 구성된 여행 코스를 회원의 저장 여정 목록에 보관합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "여정 저장 성공", content = @Content(schema = @Schema(implementation = JourneyResponse.class))),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 요청 데이터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "진행 중인 AI 런이 있거나 최대 저장 한도(30개) 초과", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/saved-journeys")
    ResponseEntity<?> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "여정 저장 요청 본문")
            @RequestBody(required = false) CreateRequest body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }
        rejectUnknownFields(body);
        if (body == null || body.explorationId() == null || body.baseVersion() == null) {
            return validation(request, "body");
        }
        var command = new CreateSavedJourneyCommand(
                member.get().id(),
                ExplorationActor.member(member.get().id()),
                body.explorationId(),
                body.baseVersion(),
                body.title());
        try {
            var key = IdempotencyKey.fromHeader(idempotencyKey);
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(),
                    "MEMBER:" + member.get().id(),
                    "POST",
                    "/api/v1/saved-journeys",
                    IdempotencyFingerprint.sha256("POST", "/api/v1/saved-journeys", "saved-journey.create", command)), () -> {
                var result = savedJourneys.save(command);
                LOGGER.atInfo()
                        .addKeyValue("member.id", member.get().id())
                        .addKeyValue("saved_journey.id", result.journey().savedJourneyId())
                        .addKeyValue("created", result.created())
                        .log("saved_journey_create");
                return result.created()
                        ? com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse.created(
                                "/api/v1/saved-journeys/" + result.journey().savedJourneyId(),
                                toResponse(result.journey()))
                        : com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse.ok(toResponse(result.journey()));
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
        } catch (SavedJourneyNotFoundException exception) {
            return notFound(request);
        } catch (ExplorationActiveRunException exception) {
            var details = exception.runId() == null ? Map.<String, Object>of() : Map.<String, Object>of("runId", exception.runId().toString());
            return conflict(request, "ACTIVE_RUN", "Active run is still in progress.", details);
        } catch (SavedJourneyLimitExceededException exception) {
            return conflict(request, "SAVE_LIMIT", "Saved journey limit exceeded.", Map.of("limit", exception.limit()));
        } catch (SavedJourneyInputInvalidException | IllegalArgumentException exception) {
            return validation(request, exception.getMessage());
        }
    }

    @Operation(
            summary = "내가 저장한 AI 여정 목록 조회",
            description = "로그인한 회원이 보관함에 저장해 둔 여정 목록을 커서 페이징 방식으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 여정 목록 조회 성공", content = @Content(schema = @Schema(implementation = PageResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/saved-journeys")
    ResponseEntity<?> list(
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
            var page = savedJourneys.list(member.get().id(), limit, cursor);
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(new PageResponse("1.2", page.items().stream().map(this::toSummary).toList(), page.nextCursor(), page.hasMore()));
        } catch (SavedJourneyInputInvalidException exception) {
            return validation(request, exception.getMessage());
        }
    }

    @Operation(
            summary = "저장된 AI 여정 상세 조회",
            description = "저장된 여정 ID(savedJourneyId)를 기반으로 코스 스냅샷, 방문 순서, 포함된 장소 상세 정보를 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 여정 상세 조회 성공", content = @Content(schema = @Schema(implementation = JourneyResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "저장 여정을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/saved-journeys/{savedJourneyId}")
    ResponseEntity<?> get(
            @Parameter(description = "저장 여정 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID savedJourneyId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(toResponse(savedJourneys.get(member.get().id(), savedJourneyId)));
        } catch (SavedJourneyNotFoundException exception) {
            return notFound(request);
        }
    }

    @Operation(
            summary = "저장된 AI 여정 삭제",
            description = "보관함에 저장된 여정을 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장 여정 삭제 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "저장 여정을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/saved-journeys/{savedJourneyId}")
    ResponseEntity<?> delete(
            @Parameter(description = "저장 여정 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID savedJourneyId,
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
            var path = "/api/v1/saved-journeys/" + savedJourneyId;
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(),
                    "MEMBER:" + member.get().id(),
                    "DELETE",
                    path,
                    IdempotencyFingerprint.sha256("DELETE", path, "saved-journey.delete", savedJourneyId)), () -> {
                savedJourneys.delete(member.get().id(), savedJourneyId);
                LOGGER.atInfo()
                        .addKeyValue("member.id", member.get().id())
                        .addKeyValue("saved_journey.id", savedJourneyId)
                        .log("saved_journey_delete");
                return new com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse(204, Map.of(), null);
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).build();
        } catch (SavedJourneyNotFoundException exception) {
            return notFound(request);
        }
    }

    @Operation(
            summary = "저장된 여정으로부터 AI 탐색 재개 (Resume)",
            description = "과거에 저장해 둔 여정 스냅샷을 기반으로 새로운 AI 대화 세션을 생성하여 여행 계획을 이어서 수정/탐색합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "AI 탐색 재개 세션 생성 완료", content = @Content(schema = @Schema(implementation = ResumeResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "저장 여정을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PostMapping("/api/v1/saved-journeys/{savedJourneyId}/resume")
    ResponseEntity<?> resume(
            @Parameter(description = "저장 여정 UUID", example = "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d")
            @PathVariable UUID savedJourneyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "빈 JSON 객체")
            @RequestBody(required = false) Map<String, Object> body,
            @Parameter(description = "멱등성 키", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }
        if (body != null && !body.isEmpty()) {
            return validation(request, "body");
        }
        try {
            var key = IdempotencyKey.fromHeader(idempotencyKey);
            var path = "/api/v1/saved-journeys/" + savedJourneyId + "/resume";
            var response = idempotency.execute(new IdempotencyCommand(
                    key.value(),
                    "MEMBER:" + member.get().id(),
                    "POST",
                    path,
                    IdempotencyFingerprint.sha256("POST", path, "saved-journey.resume", savedJourneyId)), () -> {
                var result = savedJourneys.resume(member.get().id(), savedJourneyId);
                LOGGER.atInfo()
                        .addKeyValue("member.id", member.get().id())
                        .addKeyValue("saved_journey.id", savedJourneyId)
                        .addKeyValue("unavailable.count", result.unavailableRefs().size())
                        .log("saved_journey_resume");
                return com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse.created(
                        "/api/v1/explorations/" + result.exploration().explorationId(),
                        toResume(result));
            });
            return ResponseEntity.status(response.status()).cacheControl(CacheControl.noStore()).body(response.body());
        } catch (SavedJourneyNotFoundException exception) {
            return notFound(request);
        }
    }

    private JourneyResponse toResponse(SavedJourney journey) {
        return new JourneyResponse(
                "1.2",
                journey.savedJourneyId(),
                journey.title(),
                journey.sourceExplorationId(),
                journey.sourceVersion(),
                journey.savedAt(),
                journey.candidateCount(),
                snapshot(journey.snapshot()));
    }

    private SummaryResponse toSummary(SavedJourneySummary summary) {
        return new SummaryResponse(
                summary.savedJourneyId(),
                summary.title(),
                summary.sourceExplorationId(),
                summary.sourceVersion(),
                summary.savedAt(),
                summary.candidateCount());
    }

    private SnapshotResponse snapshot(SavedJourneySnapshot snapshot) {
        return new SnapshotResponse(
                "1.2",
                board(snapshot),
                refs(snapshot.pinnedRefs()),
                refs(snapshot.excludedRefs()),
                Map.of("dataMode", "PUBLIC", "engine", "BASELINE", "rankingVersion", "baseline-v1", "datasetRevision", "catalog-current"),
                snapshot.updatedAt());
    }

    private ResumeResponse toResume(ResumeSavedJourneyResult result) {
        var exploration = result.exploration();
        Object board = exploration.orderedRefs().isEmpty()
                ? null
                : board(SavedJourneySnapshot.seed(
                        exploration.explorationId(),
                        exploration.stateVersion(),
                        "저장 여정 재개",
                        exploration.regionCode(),
                        exploration.orderedRefs(),
                        exploration.pinnedRefs(),
                        exploration.excludedRefs(),
                        Instant.now()
                ));
        return new ResumeResponse("1.2", new ExplorationResumeResponse(
                "1.2",
                exploration.explorationId(),
                exploration.stateVersion(),
                board,
                refs(exploration.pinnedRefs()),
                refs(exploration.excludedRefs()),
                refs(result.unavailableRefs()),
                Map.of("dataMode", "PUBLIC", "engine", "BASELINE", "rankingVersion", "baseline-v1", "datasetRevision", "catalog-current"),
                null,
                null,
                List.of(),
                Instant.now()), refs(result.unavailableRefs()));
    }

    private Object board(SavedJourneySnapshot snapshot) {
        var regionRef = regionRef(snapshot.regionCode());
        var candidates = snapshot.orderedRefs().stream()
                .map(place -> orderedMap(
                        "placeRef", ref(place),
                        "reason", "저장된 여정 snapshot에서 복원한 장소입니다.",
                        "evidenceRefs", List.of("evidence-saved-" + place.id()),
                        "relationRefs", List.of("relation-saved-region-" + place.id()),
                        "constraintChecks", List.of(orderedMap(
                                "key", "REGION",
                                "status", "SATISFIED",
                                "label", snapshot.regionCode(),
                                "evidenceRefs", List.of("evidence-saved-" + place.id())))))
                .toList();
        var resources = new java.util.ArrayList<Object>();
        for (var place : snapshot.orderedRefs()) {
            resources.add(orderedMap(
                    "ref", ref(place),
                    "title", place.id(),
                    "category", "PLACE",
                    "regionRef", regionRef,
                    "summary", null,
                    "image", null,
                    "location", null,
                    "sourceRefs", List.of("saved-journey"),
                    "unavailableFields", List.of("image", "location")));
        }
        resources.add(orderedMap("ref", regionRef, "title", snapshot.regionCode()));
        return orderedMap(
                "title", snapshot.title(),
                "summaryRefs", List.of(regionRef),
                "regionRef", regionRef,
                "candidates", candidates,
                "legs", List.of(),
                "resources", resources,
                "relations", List.of(),
                "evidence", List.of());
    }

    private List<Object> refs(List<ResourceRef> refs) {
        return refs.stream().map(ref -> (Object) ref(ref)).toList();
    }

    private Map<String, Object> ref(ResourceRef ref) {
        return Map.of("type", ref.type(), "id", ref.id());
    }

    private Map<String, Object> regionRef(String regionCode) {
        return Map.of("type", "REGION", "id", regionCode);
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication is required.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "저장 여정을 찾을 수 없습니다.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> validation(HttpServletRequest request, String field) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", request, Map.of("field", field == null ? "body" : field));
    }

    private ResponseEntity<ApiErrorResponse> conflict(HttpServletRequest request, String code, String message, Map<String, Object> details) {
        return error(HttpStatus.CONFLICT, code, message, request, details);
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

    private void rejectUnknownFields(UnknownFieldAware value) {
        if (value != null && !value.unknownFields().isEmpty()) {
            throw new SavedJourneyInputInvalidException("body");
        }
    }

    private static Map<String, Object> orderedMap(Object... entries) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) {
            values.put((String) entries[index], entries[index + 1]);
        }
        return values;
    }

    private interface UnknownFieldAware {
        Map<String, Object> unknownFields();
    }

    private record CreateRequest(UUID explorationId, Integer baseVersion, String title, Map<String, Object> unknownFields)
            implements UnknownFieldAware {
        @JsonCreator
        private CreateRequest(
                @JsonProperty("explorationId") UUID explorationId,
                @JsonProperty("baseVersion") Integer baseVersion,
                @JsonProperty("title") String title) {
            this(explorationId, baseVersion, title, new LinkedHashMap<>());
        }

        @JsonAnySetter
        void unknown(String name, Object value) {
            unknownFields.put(name, value);
        }
    }

    private record SummaryResponse(
            UUID savedJourneyId,
            String title,
            UUID sourceExplorationId,
            int sourceVersion,
            Instant savedAt,
            int candidateCount) {
    }

    private record JourneyResponse(
            String schemaVersion,
            UUID savedJourneyId,
            String title,
            UUID sourceExplorationId,
            int sourceVersion,
            Instant savedAt,
            int candidateCount,
            SnapshotResponse snapshot) {
    }

    private record SnapshotResponse(
            String schemaVersion,
            Object board,
            List<Object> pinnedRefs,
            List<Object> excludedRefs,
            Map<String, Object> execution,
            Instant updatedAt) {
    }

    private record PageResponse(String schemaVersion, List<SummaryResponse> items, String nextCursor, boolean hasMore) {
    }

    private record ResumeResponse(String schemaVersion, ExplorationResumeResponse exploration, List<Object> unavailableRefs) {
    }

    private record ExplorationResumeResponse(
            String schemaVersion,
            UUID explorationId,
            int stateVersion,
            Object board,
            List<Object> pinnedRefs,
            List<Object> excludedRefs,
            List<Object> unavailableRefs,
            Map<String, Object> execution,
            Object latestRun,
            Object pendingProposal,
            List<Object> recentHistory,
            Instant updatedAt) {
    }
}
