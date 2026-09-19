package com.yrootlab.onmaru.web.saved.list;

import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.audio.query.OdiiStoryUnavailableException;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailUnavailableException;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjectionStatus;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryLimitExceededException;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryNotFoundException;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryService;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Tag(name = "05. 개인화 & 타임라인 (Saved & Timeline)", description = "북마크/저장한 장소 및 오디오 도슨트, 내 활동 타임라인 및 회원 프로필 API")
@RestController
public final class SavedResourceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final Comparator<SavedResourceRecord> ORDER = Comparator
            .comparing(SavedResourceRecord::savedAt).reversed()
            .thenComparing(SavedResourceRecord::id, Comparator.reverseOrder());

    private final MemberLifecycleService members;
    private final SavedOdiiStoryService odiiSaveService;
    private final SavedOdiiStoryStore odiiStore;
    private final InMemorySavedPlaceStore placeStore;
    private final OdiiStoryQueryService odiiQueries;
    private final InMemoryPlaceDetailStore placeDetails;
    private final SavedResourceCursorCodec cursors;

    SavedResourceController(
            MemberLifecycleService members,
            SavedOdiiStoryService odiiSaveService,
            SavedOdiiStoryStore odiiStore,
            InMemorySavedPlaceStore placeStore,
            OdiiStoryQueryService odiiQueries,
            InMemoryPlaceDetailStore placeDetails,
            SavedResourceCursorCodec cursors) {
        this.members = members;
        this.odiiSaveService = odiiSaveService;
        this.odiiStore = odiiStore;
        this.placeStore = placeStore;
        this.odiiQueries = odiiQueries;
        this.placeDetails = placeDetails;
        this.cursors = cursors;
    }

    @Operation(
            summary = "오디 오디오 도슨트 스토리 북마크/저장",
            description = "특정 오디 오디오 스토리를 내 보관함에 저장(북마크)합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "오디오 스토리 저장 성공"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "오디오 스토리를 찾을 수 없음", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "최대 저장 한도 초과", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @PutMapping("/api/v1/saved-resources/odii-stories/{storyId}")
    ResponseEntity<?> saveOdii(
            @Parameter(description = "오디 스토리 ID", example = "story-gyeongbokgung-01")
            @PathVariable String storyId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        return members.currentMember(session).<ResponseEntity<?>>map(member -> {
            try {
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .body(odiiSaveService.save(member.id(), storyId));
            } catch (SavedOdiiStoryNotFoundException exception) {
                return notFound(request);
            } catch (SavedOdiiStoryLimitExceededException exception) {
                return saveLimit(request, exception.limit());
            } catch (OdiiStoryUnavailableException exception) {
                return unavailable(request);
            }
        }).orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "오디 오디오 도슨트 스토리 북마크/저장 취소",
            description = "보관함에 저장해 둔 오디 오디오 스토리를 삭제합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "저장 취소 완료"),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @DeleteMapping("/api/v1/saved-resources/odii-stories/{storyId}")
    ResponseEntity<?> deleteOdii(
            @Parameter(description = "오디 스토리 ID", example = "story-gyeongbokgung-01")
            @PathVariable String storyId,
            @Parameter(description = "회원 세션 쿠키", hidden = true)
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        return members.currentMember(session).<ResponseEntity<?>>map(member -> {
            odiiSaveService.delete(member.id(), storyId);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }).orElseGet(() -> authRequired(request));
    }

    @Operation(
            summary = "내가 저장한 리소스 목록 조회 (PLACE / ODII_STORY)",
            description = "타입(type: PLACE 또는 ODII_STORY)별로 회원이 저장한 리소스 목록을 커서 페이징 방식으로 조회합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "저장 리소스 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "유효하지 않은 타입 또는 파라미터", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class))),
            @ApiResponse(responseCode = "401", description = "로그인 세션 필요", content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
    })
    @GetMapping("/api/v1/saved-resources")
    ResponseEntity<?> list(
            @Parameter(description = "저장 리소스 타입 (PLACE, ODII_STORY)", example = "PLACE", required = true)
            @RequestParam(required = false) String type,
            @Parameter(description = "조회 개수 (기본값 20, 최대 50)", example = "20")
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
        if (type == null || type.isBlank()) {
            return validation(request, "type");
        }
        if (limit < 1 || limit > 50) {
            return validation(request, "limit");
        }
        final SavedResourceType resourceType;
        try {
            resourceType = SavedResourceType.valueOf(type);
        } catch (IllegalArgumentException exception) {
            return validation(request, "type");
        }
        try {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                    .body(page(member.get().id(), resourceType, limit, cursor));
        } catch (ActorCursorMismatchException exception) {
            return notFound(request);
        } catch (CursorInvalidException exception) {
            return error(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "Cursor is invalid.", request, Map.of());
        } catch (CursorExpiredException exception) {
            return error(HttpStatus.GONE, "CURSOR_EXPIRED", "Cursor is expired.", request, Map.of());
        } catch (OdiiStoryUnavailableException | PlaceDetailUnavailableException exception) {
            return unavailable(request);
        }
    }

    private SavedResourcePage page(UUID memberId, SavedResourceType type, int limit, String cursorValue) {
        if (cursorValue != null && cursorValue.length() > 512) {
            throw new CursorInvalidException();
        }
        var cursor = cursorValue == null ? null : cursors.decode(cursorValue);
        var asOf = cursor == null ? cursors.now() : cursor.asOf();
        if (cursor != null && !cursor.memberId().equals(memberId)) {
            throw new ActorCursorMismatchException();
        }
        if (cursor != null && (cursor.type() != type || cursor.limit() != limit)) {
            throw new CursorInvalidException();
        }
        var records = (type == SavedResourceType.PLACE
                ? placeStore.records(memberId, type)
                : odiiStore.records(memberId, type)).stream()
                .filter(record -> !record.savedAt().isAfter(asOf))
                .sorted(ORDER)
                .filter(record -> cursor == null || after(record, cursor))
                .toList();
        var hydrated = new ArrayList<Hydrated>();
        for (var record : records) {
            hydrate(memberId, record).ifPresent(hydrated::add);
        }
        boolean hasMore = hydrated.size() > limit;
        var selected = hasMore ? hydrated.subList(0, limit) : hydrated;
        String nextCursor = hasMore
                ? cursors.encode(new SavedResourceCursorCodec.Cursor(
                        memberId, type, limit, asOf,
                        selected.getLast().record().savedAt(), selected.getLast().record().id()))
                : null;
        return new SavedResourcePage("1.2", selected.stream().map(Hydrated::value).toList(), nextCursor, hasMore);
    }

    private Optional<Hydrated> hydrate(UUID memberId, SavedResourceRecord record) {
        if (record.resourceType() == SavedResourceType.ODII_STORY) {
            try {
                var story = odiiQueries.savedStory(record.resourceId(), Optional.of(memberId));
                return Optional.of(new Hydrated(record, new SavedOdiiStorySummary(
                        "1.2", SavedResourceType.ODII_STORY, story.storyId(), story.storyId(), story.spotId(),
                        story.title(), story.placeId(), story.durationSeconds(), true, record.savedAt())));
            } catch (OdiiStoryNotFoundException exception) {
                return Optional.empty();
            }
        }
        return placeDetails.findByPlaceId(record.resourceId())
                .filter(place -> place.status() == PlaceProjectionStatus.PUBLIC && !place.ambiguousMapping())
                .map(place -> new Hydrated(record, new SavedPlaceSummary(
                        "1.2", SavedResourceType.PLACE, place.placeId(), place.placeId(), place.name(), place.category(),
                        place.region().name(), place.images().isEmpty() ? null : place.images().getFirst().url(),
                        true, record.savedAt())));
    }

    private boolean after(SavedResourceRecord record, SavedResourceCursorCodec.Cursor cursor) {
        int time = record.savedAt().compareTo(cursor.savedAt());
        return time < 0 || time == 0 && record.id().compareTo(cursor.lastId()) < 0;
    }

    private ResponseEntity<ApiErrorResponse> authRequired(HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication is required.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> notFound(HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> validation(HttpServletRequest request, String field) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", request, Map.of("field", field));
    }

    private ResponseEntity<ApiErrorResponse> saveLimit(HttpServletRequest request, int limit) {
        return error(HttpStatus.CONFLICT, "SAVE_LIMIT", "Saved resource limit exceeded.", request, Map.of("limit", limit));
    }

    private ResponseEntity<ApiErrorResponse> unavailable(HttpServletRequest request) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "SERVICE_UNAVAILABLE",
                "Saved resources are temporarily unavailable.",
                request,
                Map.of("retryAfterMs", 30000));
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status, String code, String message, HttpServletRequest request, Map<String, Object> details) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore())
                .body(new ApiErrorResponse("1.2", code, message, requestId(request), details));
    }

    private String requestId(HttpServletRequest request) {
        var value = request.getAttribute(RequestIdFilter.ATTRIBUTE);
        return value instanceof String id && !id.isBlank() ? id : UUID.randomUUID().toString();
    }

    private static final class ActorCursorMismatchException extends RuntimeException {
    }

    private record Hydrated(SavedResourceRecord record, Object value) {
    }

    record SavedResourcePage(String schemaVersion, List<Object> items, String nextCursor, boolean hasMore) {
    }

    record SavedOdiiStorySummary(
            String schemaVersion, SavedResourceType resourceType, String resourceId, String storyId,
            String spotId, String title, String placeId, Integer durationSeconds, boolean savedByMe, Instant savedAt) {
    }

    record SavedPlaceSummary(
            String schemaVersion, SavedResourceType resourceType, String resourceId, String placeId,
            String name, String category, String regionName, String thumbnailUrl, boolean savedByMe, Instant savedAt) {
    }
}
