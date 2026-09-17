package com.yrootlab.onmaru.web.me.timeline;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.journey.timeline.MemberTimelineService;
import com.yrootlab.onmaru.journey.timeline.TimelineCursor;
import com.yrootlab.onmaru.journey.timeline.TimelineDayGroup;
import com.yrootlab.onmaru.journey.timeline.TimelineItem;
import com.yrootlab.onmaru.journey.timeline.TimelineItemType;
import com.yrootlab.onmaru.journey.timeline.TimelinePage;
import com.yrootlab.onmaru.journey.timeline.TimelineTarget;
import com.yrootlab.onmaru.web.common.cursor.CursorExpiredException;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.error.ApiErrorResponse;
import com.yrootlab.onmaru.web.common.error.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@RestController
public final class MemberTimelineController {

    private static final Logger LOGGER = LoggerFactory.getLogger(MemberTimelineController.class);
    private static final String SESSION_COOKIE = "__Host-onmaru-session";
    private static final Pattern MONTH_PATTERN = Pattern.compile("^[0-9]{4}-[0-9]{2}$");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final MemberTimelineService timelineService;
    private final MemberLifecycleService members;
    private final MemberTimelineCursorCodec cursorCodec;

    MemberTimelineController(
            MemberTimelineService timelineService,
            MemberLifecycleService members,
            MemberTimelineCursorCodec cursorCodec) {
        this.timelineService = Objects.requireNonNull(timelineService, "timelineService");
        this.members = Objects.requireNonNull(members, "members");
        this.cursorCodec = Objects.requireNonNull(cursorCodec, "cursorCodec");
    }

    @GetMapping("/api/v1/me/timeline")
    ResponseEntity<?> getTimeline(
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor,
            @CookieValue(name = SESSION_COOKIE, required = false) String session,
            HttpServletRequest request) {
        var member = members.currentMember(session);
        if (member.isEmpty()) {
            return authRequired(request);
        }

        final YearMonth parsedMonth;
        if (month == null || month.isBlank()) {
            parsedMonth = YearMonth.now(KST);
        } else {
            if (!MONTH_PATTERN.matcher(month).matches()) {
                return validation(request, "month");
            }
            try {
                parsedMonth = YearMonth.parse(month);
            } catch (DateTimeParseException exception) {
                return validation(request, "month");
            }
        }

        if (limit < 1 || limit > 50) {
            return validation(request, "limit");
        }

        TimelineCursor decodedCursor = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                decodedCursor = cursorCodec.decode(cursor);
            } catch (CursorInvalidException exception) {
                return cursorInvalid(request);
            } catch (CursorExpiredException exception) {
                return cursorExpired(request);
            }

            if (!decodedCursor.memberId().equals(member.get().id())) {
                return notFound(request);
            }
            if (!decodedCursor.month().equals(parsedMonth) || decodedCursor.limit() != limit) {
                return cursorInvalid(request);
            }
        }

        TimelinePage page = timelineService.getTimeline(member.get().id(), parsedMonth, limit, decodedCursor);

        String nextCursorString = page.nextCursor() != null
                ? cursorCodec.encode(page.nextCursor())
                : null;

        LOGGER.atInfo()
                .addKeyValue("member.id", member.get().id())
                .addKeyValue("month", parsedMonth.toString())
                .addKeyValue("limit", limit)
                .addKeyValue("timeline.group_count", page.groups().size())
                .addKeyValue("timeline.unavailable_count", page.unavailableCount())
                .addKeyValue("timeline.has_more", page.hasMore())
                .log("member_timeline_queried");

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(toResponse(page, nextCursorString));
    }

    private MemberTimelineResponse toResponse(TimelinePage page, String nextCursor) {
        return new MemberTimelineResponse(
                page.schemaVersion(),
                page.month(),
                page.groups().stream().map(this::toGroupResponse).toList(),
                nextCursor,
                page.hasMore(),
                page.unavailableCount());
    }

    private TimelineGroupResponse toGroupResponse(TimelineDayGroup group) {
        return new TimelineGroupResponse(
                group.date(),
                group.items().stream().map(this::toItemResponse).toList());
    }

    private TimelineItemResponse toItemResponse(TimelineItem item) {
        return new TimelineItemResponse(
                item.id(),
                item.type(),
                item.occurredAt(),
                item.title(),
                item.subtitle(),
                item.thumbnailUrl(),
                toTargetResponse(item.target()));
    }

    private Object toTargetResponse(TimelineTarget target) {
        if (target instanceof TimelineTarget.PlaceTimelineTarget place) {
            return new PlaceTargetResponse("PLACE", place.placeId());
        }
        if (target instanceof TimelineTarget.OdiiStoryTimelineTarget odii) {
            return new OdiiStoryTargetResponse("ODII_STORY", odii.storyId(), odii.placeId());
        }
        if (target instanceof TimelineTarget.SavedJourneyTimelineTarget journey) {
            return new SavedJourneyTargetResponse("SAVED_JOURNEY", journey.savedJourneyId());
        }
        if (target instanceof TimelineTarget.VisitReviewTimelineTarget review) {
            return new VisitReviewTargetResponse("VISIT_REVIEW", review.reviewId(), review.placeId());
        }
        throw new IllegalStateException("Unknown timeline target: " + target);
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

    private ResponseEntity<ApiErrorResponse> cursorInvalid(HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "CURSOR_INVALID", "Cursor is invalid.", request, Map.of());
    }

    private ResponseEntity<ApiErrorResponse> cursorExpired(HttpServletRequest request) {
        return error(HttpStatus.GONE, "CURSOR_EXPIRED", "Cursor is expired.", request, Map.of());
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

    record MemberTimelineResponse(
            String schemaVersion,
            String month,
            List<TimelineGroupResponse> groups,
            String nextCursor,
            boolean hasMore,
            int unavailableCount) {
    }

    record TimelineGroupResponse(
            String date,
            List<TimelineItemResponse> items) {
    }

    record TimelineItemResponse(
            String id,
            TimelineItemType type,
            Instant occurredAt,
            String title,
            String subtitle,
            String thumbnailUrl,
            Object target) {
    }

    record PlaceTargetResponse(String type, String placeId) {
    }

    record OdiiStoryTargetResponse(String type, String storyId, String placeId) {
    }

    record SavedJourneyTargetResponse(String type, String savedJourneyId) {
    }

    record VisitReviewTargetResponse(String type, String reviewId, String placeId) {
    }
}
