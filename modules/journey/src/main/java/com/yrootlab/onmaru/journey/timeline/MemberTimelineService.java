package com.yrootlab.onmaru.journey.timeline;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecordSource;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneyStore;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySummary;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MemberTimelineService {

    private static final String SCHEMA_VERSION = "1.2";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Comparator<TimelineItem> ITEM_ORDER = Comparator
            .comparing(TimelineItem::occurredAt).reversed()
            .thenComparing(TimelineItem::id, Comparator.reverseOrder());

    private final SavedResourceRecordSource placeStore;
    private final TimelinePlaceLookup placeLookup;
    private final SavedResourceRecordSource odiiStore;
    private final TimelineOdiiStoryLookup odiiLookup;
    private final SavedJourneyStore journeyStore;
    private final TimelineVisitReviewSource reviewSource;
    private final Clock clock;

    public MemberTimelineService(
            SavedResourceRecordSource placeStore,
            TimelinePlaceLookup placeLookup,
            SavedResourceRecordSource odiiStore,
            TimelineOdiiStoryLookup odiiLookup,
            SavedJourneyStore journeyStore,
            TimelineVisitReviewSource reviewSource,
            Clock clock) {
        this.placeStore = Objects.requireNonNull(placeStore, "placeStore");
        this.placeLookup = Objects.requireNonNull(placeLookup, "placeLookup");
        this.odiiStore = Objects.requireNonNull(odiiStore, "odiiStore");
        this.odiiLookup = Objects.requireNonNull(odiiLookup, "odiiLookup");
        this.journeyStore = Objects.requireNonNull(journeyStore, "journeyStore");
        this.reviewSource = Objects.requireNonNull(reviewSource, "reviewSource");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public TimelinePage getTimeline(UUID memberId, YearMonth month, int limit, TimelineCursor cursor) {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(month, "month");

        Instant startOfMonth = month.atDay(1).atStartOfDay(KST).toInstant();
        Instant endOfMonth = month.plusMonths(1).atDay(1).atStartOfDay(KST).toInstant();
        Instant asOf = (cursor != null) ? cursor.asOf() : clock.instant();

        int unavailableCount = 0;
        List<TimelineItem> validItems = new ArrayList<>();

        // 1. SAVED_PLACE
        List<SavedResourceRecord> placeRecords = placeStore.records(memberId, SavedResourceType.PLACE);
        for (SavedResourceRecord record : placeRecords) {
            Instant occurredAt = record.savedAt();
            if (isInMonth(occurredAt, startOfMonth, endOfMonth) && !occurredAt.isAfter(asOf)) {
                var metadata = placeLookup.lookup(record.resourceId());
                if (metadata.isPresent()) {
                    var m = metadata.get();
                    String subtitle = m.category() + " · " + m.regionName();
                    validItems.add(new TimelineItem(
                            "tl-" + record.id(),
                            TimelineItemType.SAVED_PLACE,
                            occurredAt,
                            m.name(),
                            subtitle,
                            m.thumbnailUrl(),
                            new TimelineTarget.PlaceTimelineTarget(record.resourceId())));
                } else {
                    unavailableCount++;
                }
            }
        }

        // 2. SAVED_ODII_STORY
        List<SavedResourceRecord> odiiRecords = odiiStore.records(memberId, SavedResourceType.ODII_STORY);
        for (SavedResourceRecord record : odiiRecords) {
            Instant occurredAt = record.savedAt();
            if (isInMonth(occurredAt, startOfMonth, endOfMonth) && !occurredAt.isAfter(asOf)) {
                var metadata = odiiLookup.lookup(record.resourceId(), memberId);
                if (metadata.isPresent()) {
                    var m = metadata.get();
                    validItems.add(new TimelineItem(
                            "tl-" + record.id(),
                            TimelineItemType.SAVED_ODII_STORY,
                            occurredAt,
                            m.title(),
                            "오디오 이야기",
                            null,
                            new TimelineTarget.OdiiStoryTimelineTarget(record.resourceId(), m.placeId())));
                } else {
                    unavailableCount++;
                }
            }
        }

        // 3. SAVED_JOURNEY
        List<SavedJourneySummary> journeys = journeyStore.list(memberId);
        for (SavedJourneySummary journey : journeys) {
            Instant occurredAt = journey.savedAt();
            if (isInMonth(occurredAt, startOfMonth, endOfMonth) && !occurredAt.isAfter(asOf)) {
                validItems.add(new TimelineItem(
                        "tl-" + journey.savedJourneyId(),
                        TimelineItemType.SAVED_JOURNEY,
                        occurredAt,
                        journey.title(),
                        "저장한 여정",
                        null,
                        new TimelineTarget.SavedJourneyTimelineTarget(journey.savedJourneyId().toString())));
            }
        }

        // 4. WROTE_VISIT_REVIEW
        List<TimelineVisitReviewRecord> reviews = reviewSource.records(memberId);
        for (TimelineVisitReviewRecord review : reviews) {
            Instant occurredAt = review.createdAt();
            if (isInMonth(occurredAt, startOfMonth, endOfMonth) && !occurredAt.isAfter(asOf)) {
                if (review.isPublic()) {
                    validItems.add(new TimelineItem(
                            "tl-" + review.reviewId(),
                            TimelineItemType.WROTE_VISIT_REVIEW,
                            occurredAt,
                            review.placeName(),
                            "방문 후기",
                            null,
                            new TimelineTarget.VisitReviewTimelineTarget(review.reviewId(), review.placeId())));
                } else {
                    unavailableCount++;
                }
            }
        }

        // Sort all valid items by occurredAt DESC, id DESC
        validItems.sort(ITEM_ORDER);

        // Apply cursor position filter if present
        List<TimelineItem> filteredItems = validItems.stream()
                .filter(item -> cursor == null || isAfterCursor(item, cursor))
                .toList();

        // Paginate
        boolean hasMore = filteredItems.size() > limit;
        List<TimelineItem> selectedItems = hasMore ? filteredItems.subList(0, limit) : filteredItems;

        TimelineCursor nextCursor = hasMore
                ? new TimelineCursor(
                        memberId,
                        month,
                        limit,
                        asOf,
                        selectedItems.getLast().occurredAt(),
                        selectedItems.getLast().id())
                : null;

        // Group by KST day (YYYY-MM-DD)
        LinkedHashMap<String, List<TimelineItem>> groupedByDay = new LinkedHashMap<>();
        for (TimelineItem item : selectedItems) {
            String dateStr = item.occurredAt().atZone(KST).toLocalDate().toString();
            groupedByDay.computeIfAbsent(dateStr, k -> new ArrayList<>()).add(item);
        }

        List<TimelineDayGroup> dayGroups = groupedByDay.entrySet().stream()
                .map(entry -> new TimelineDayGroup(entry.getKey(), entry.getValue()))
                .toList();

        return new TimelinePage(
                SCHEMA_VERSION,
                month.toString(),
                dayGroups,
                nextCursor,
                hasMore,
                unavailableCount);
    }

    private static boolean isInMonth(Instant instant, Instant startOfMonth, Instant endOfMonth) {
        return !instant.isBefore(startOfMonth) && instant.isBefore(endOfMonth);
    }

    private static boolean isAfterCursor(TimelineItem item, TimelineCursor cursor) {
        int timeComparison = item.occurredAt().compareTo(cursor.lastOccurredAt());
        if (timeComparison < 0) {
            return true;
        }
        if (timeComparison > 0) {
            return false;
        }
        return item.id().compareTo(cursor.lastId()) < 0;
    }
}
