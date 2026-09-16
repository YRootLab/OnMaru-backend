package com.yrootlab.onmaru.web.saved.list;

import com.yrootlab.onmaru.audio.query.OdiiStoryNotFoundException;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
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

@RestController
public final class SavedResourceController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final Comparator<SavedResourceRecord> ORDER = Comparator
            .comparing(SavedResourceRecord::savedAt).reversed()
            .thenComparing(SavedResourceRecord::resourceId, Comparator.reverseOrder());

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

    @PutMapping("/api/v1/saved-resources/odii-stories/{storyId}")
    ResponseEntity<?> saveOdii(
            @PathVariable String storyId,
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
            }
        }).orElseGet(() -> authRequired(request));
    }

    @DeleteMapping("/api/v1/saved-resources/odii-stories/{storyId}")
    ResponseEntity<?> deleteOdii(
            @PathVariable String storyId,
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        return members.currentMember(session).<ResponseEntity<?>>map(member -> {
            odiiSaveService.delete(member.id(), storyId);
            return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
        }).orElseGet(() -> authRequired(request));
    }

    @GetMapping("/api/v1/saved-resources")
    ResponseEntity<?> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
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
                        selected.getLast().record().savedAt(), selected.getLast().record().resourceId()))
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
        return time < 0 || time == 0 && record.resourceId().compareTo(cursor.resourceId()) < 0;
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
