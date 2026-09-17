package com.yrootlab.onmaru.journey.timeline;

import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecord;
import com.yrootlab.onmaru.journey.saved.list.SavedResourceRecordSource;
import com.yrootlab.onmaru.journey.saved.place.SavedResourceType;
import com.yrootlab.onmaru.journey.savedjourney.InMemorySavedJourneyStore;
import com.yrootlab.onmaru.journey.savedjourney.SavedJourneySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class MemberTimelineServiceTests {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), KST);

    private FakeSavedResourceRecordSource placeStore;
    private FakeSavedResourceRecordSource odiiStore;
    private InMemorySavedJourneyStore journeyStore;
    private FakeTimelinePlaceLookup placeLookup;
    private FakeTimelineOdiiStoryLookup odiiLookup;
    private FakeTimelineVisitReviewSource reviewSource;
    private MemberTimelineService timelineService;

    private final UUID memberId = UUID.randomUUID();
    private final UUID otherMemberId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        placeStore = new FakeSavedResourceRecordSource();
        odiiStore = new FakeSavedResourceRecordSource();
        journeyStore = new InMemorySavedJourneyStore();
        placeLookup = new FakeTimelinePlaceLookup();
        odiiLookup = new FakeTimelineOdiiStoryLookup();
        reviewSource = new FakeTimelineVisitReviewSource();

        timelineService = new MemberTimelineService(
                placeStore,
                placeLookup,
                odiiStore,
                odiiLookup,
                journeyStore,
                reviewSource,
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("KST 월 경계에 속하는 찜, 오디, 여정, 후기 4종 이벤트를 시간 역순 및 날짜별로 그룹화한다")
    void aggregatesFourEventTypesInKstMonth() {
        // 2026-09-14T17:10:00 KST (2026-09-14T08:10:00Z) -> SAVED_PLACE
        placeStore.add(memberId, SavedResourceType.PLACE, "p-jeonju-01", Instant.parse("2026-09-14T08:10:00Z"));
        placeLookup.register("p-jeonju-01", "전주 한옥마을", "한옥", "전북 전주시", "https://cdn.example.com/p1.jpg");

        // 2026-09-14T16:40:00 KST (2026-09-14T07:40:00Z) -> SAVED_ODII_STORY
        odiiStore.add(memberId, SavedResourceType.ODII_STORY, "odii-story-01", Instant.parse("2026-09-14T07:40:00Z"));
        odiiLookup.register("odii-story-01", "전주의 한옥 골목 이야기", "p-jeonju-01");

        // 2026-09-13T20:20:00 KST (2026-09-13T11:20:00Z) -> SAVED_JOURNEY
        var journeySnapshot = SavedJourneySnapshot.seed(
                UUID.randomUUID(), 1, "전주 하루 여정", "jeonju", List.of(), List.of(), List.of(), Instant.parse("2026-09-13T11:20:00Z")
        );
        journeyStore.save(memberId, journeySnapshot, Instant.parse("2026-09-13T11:20:00Z"), 20);

        // 2026-09-12T10:00:00 KST (2026-09-12T01:00:00Z) -> WROTE_VISIT_REVIEW
        reviewSource.add(memberId, "vr-001", "p-jeonju-01", "전주 한옥마을", Instant.parse("2026-09-12T01:00:00Z"), true);

        // August event (should not appear in September)
        placeStore.add(memberId, SavedResourceType.PLACE, "p-august", Instant.parse("2026-08-31T14:59:59Z")); // 23:59:59 KST
        placeLookup.register("p-august", "8월 장소", "기타", "서울", null);

        var result = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 20, null);

        assertThat(result.schemaVersion()).isEqualTo("1.2");
        assertThat(result.month()).isEqualTo("2026-09");
        assertThat(result.unavailableCount()).isEqualTo(0);
        assertThat(result.hasMore()).isFalse();
        assertThat(result.nextCursor()).isNull();
        assertThat(result.groups()).hasSize(3);

        // Group 1: 2026-09-14 (2 items: PLACE, ODII_STORY)
        var group1 = result.groups().get(0);
        assertThat(group1.date()).isEqualTo("2026-09-14");
        assertThat(group1.items()).hasSize(2);
        assertThat(group1.items().get(0).type()).isEqualTo(TimelineItemType.SAVED_PLACE);
        assertThat(group1.items().get(0).title()).isEqualTo("전주 한옥마을");
        assertThat(group1.items().get(0).subtitle()).isEqualTo("한옥 · 전북 전주시");
        assertThat(group1.items().get(0).thumbnailUrl()).isEqualTo("https://cdn.example.com/p1.jpg");
        assertThat(group1.items().get(0).target()).isEqualTo(new TimelineTarget.PlaceTimelineTarget("p-jeonju-01"));

        assertThat(group1.items().get(1).type()).isEqualTo(TimelineItemType.SAVED_ODII_STORY);
        assertThat(group1.items().get(1).title()).isEqualTo("전주의 한옥 골목 이야기");
        assertThat(group1.items().get(1).subtitle()).isEqualTo("오디오 이야기");
        assertThat(group1.items().get(1).target()).isEqualTo(new TimelineTarget.OdiiStoryTimelineTarget("odii-story-01", "p-jeonju-01"));

        // Group 2: 2026-09-13 (1 item: SAVED_JOURNEY)
        var group2 = result.groups().get(1);
        assertThat(group2.date()).isEqualTo("2026-09-13");
        assertThat(group2.items()).hasSize(1);
        assertThat(group2.items().get(0).type()).isEqualTo(TimelineItemType.SAVED_JOURNEY);
        assertThat(group2.items().get(0).title()).isEqualTo("전주 하루 여정");
        assertThat(group2.items().get(0).subtitle()).isEqualTo("저장한 여정");

        // Group 3: 2026-09-12 (1 item: WROTE_VISIT_REVIEW)
        var group3 = result.groups().get(2);
        assertThat(group3.date()).isEqualTo("2026-09-12");
        assertThat(group3.items()).hasSize(1);
        assertThat(group3.items().get(0).type()).isEqualTo(TimelineItemType.WROTE_VISIT_REVIEW);
        assertThat(group3.items().get(0).title()).isEqualTo("전주 한옥마을");
        assertThat(group3.items().get(0).subtitle()).isEqualTo("방문 후기");
        assertThat(group3.items().get(0).target()).isEqualTo(new TimelineTarget.VisitReviewTimelineTarget("vr-001", "p-jeonju-01"));
    }

    @Test
    @DisplayName("비공개/삭제된 리소스는 목록에서 제외되고 unavailableCount만 증가한다")
    void unavailableResourcesAreExcludedAndCounted() {
        // Valid place
        placeStore.add(memberId, SavedResourceType.PLACE, "p-valid", Instant.parse("2026-09-14T08:10:00Z"));
        placeLookup.register("p-valid", "유효 장소", "한옥", "전주", null);

        // Deleted / Hidden place (not in lookup)
        placeStore.add(memberId, SavedResourceType.PLACE, "p-deleted", Instant.parse("2026-09-14T07:10:00Z"));

        // Deleted Odii story (not in lookup)
        odiiStore.add(memberId, SavedResourceType.ODII_STORY, "odii-deleted", Instant.parse("2026-09-13T05:00:00Z"));

        // Hidden review
        reviewSource.add(memberId, "vr-hidden", "p-valid", "유효 장소", Instant.parse("2026-09-12T02:00:00Z"), false);

        var result = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 20, null);

        assertThat(result.unavailableCount()).isEqualTo(3);
        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().getFirst().items()).hasSize(1);
        assertThat(result.groups().getFirst().items().getFirst().title()).isEqualTo("유효 장소");
    }

    @Test
    @DisplayName("타 회원의 활동 데이터는 격리되어 조회되지 않는다")
    void isolatesMemberData() {
        placeStore.add(otherMemberId, SavedResourceType.PLACE, "p-other", Instant.parse("2026-09-14T08:10:00Z"));
        placeLookup.register("p-other", "타인 장소", "한옥", "전주", null);

        var result = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 20, null);

        assertThat(result.groups()).isEmpty();
        assertThat(result.unavailableCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("커서 기반 페이지네이션이 정확히 동작한다")
    void cursorPaginationWorksCorrectly() {
        for (int i = 1; i <= 5; i++) {
            String placeId = "p-" + i;
            placeStore.add(memberId, SavedResourceType.PLACE, placeId, Instant.parse("2026-09-10T10:0" + i + ":00Z"));
            placeLookup.register(placeId, "장소 " + i, "카테고리", "지역", null);
        }

        // Page 1 with limit 2
        var page1 = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 2, null);
        assertThat(page1.groups()).hasSize(1);
        assertThat(page1.groups().getFirst().items()).hasSize(2);
        assertThat(page1.hasMore()).isTrue();
        assertThat(page1.nextCursor()).isNotNull();
        assertThat(page1.groups().getFirst().items().get(0).title()).isEqualTo("장소 5");
        assertThat(page1.groups().getFirst().items().get(1).title()).isEqualTo("장소 4");

        // Page 2 with cursor
        var page2 = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 2, page1.nextCursor());
        assertThat(page2.groups()).hasSize(1);
        assertThat(page2.groups().getFirst().items()).hasSize(2);
        assertThat(page2.hasMore()).isTrue();
        assertThat(page2.nextCursor()).isNotNull();
        assertThat(page2.groups().getFirst().items().get(0).title()).isEqualTo("장소 3");
        assertThat(page2.groups().getFirst().items().get(1).title()).isEqualTo("장소 2");

        // Page 3 with cursor (last item)
        var page3 = timelineService.getTimeline(memberId, YearMonth.of(2026, 9), 2, page2.nextCursor());
        assertThat(page3.groups()).hasSize(1);
        assertThat(page3.groups().getFirst().items()).hasSize(1);
        assertThat(page3.hasMore()).isFalse();
        assertThat(page3.nextCursor()).isNull();
        assertThat(page3.groups().getFirst().items().get(0).title()).isEqualTo("장소 1");
    }

    private static final class FakeSavedResourceRecordSource implements SavedResourceRecordSource {
        private final Map<UUID, List<SavedResourceRecord>> storage = new ConcurrentHashMap<>();

        void add(UUID memberId, SavedResourceType type, String resourceId, Instant savedAt) {
            storage.computeIfAbsent(memberId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(new SavedResourceRecord(UUID.randomUUID(), type, resourceId, savedAt));
        }

        @Override
        public List<SavedResourceRecord> records(UUID memberId, SavedResourceType resourceType) {
            return storage.getOrDefault(memberId, List.of()).stream()
                    .filter(r -> r.resourceType() == resourceType)
                    .toList();
        }
    }

    private static final class FakeTimelinePlaceLookup implements TimelinePlaceLookup {
        private final Map<String, TimelinePlaceMetadata> places = new ConcurrentHashMap<>();

        void register(String placeId, String name, String category, String regionName, String thumbnailUrl) {
            places.put(placeId, new TimelinePlaceMetadata(name, category, regionName, thumbnailUrl));
        }

        @Override
        public Optional<TimelinePlaceMetadata> lookup(String placeId) {
            return Optional.ofNullable(places.get(placeId));
        }
    }

    private static final class FakeTimelineOdiiStoryLookup implements TimelineOdiiStoryLookup {
        private final Map<String, TimelineOdiiMetadata> stories = new ConcurrentHashMap<>();

        void register(String storyId, String title, String placeId) {
            stories.put(storyId, new TimelineOdiiMetadata(title, placeId));
        }

        @Override
        public Optional<TimelineOdiiMetadata> lookup(String storyId, UUID memberId) {
            return Optional.ofNullable(stories.get(storyId));
        }
    }

    private static final class FakeTimelineVisitReviewSource implements TimelineVisitReviewSource {
        private final Map<UUID, List<TimelineVisitReviewRecord>> reviews = new ConcurrentHashMap<>();

        void add(UUID memberId, String reviewId, String placeId, String placeName, Instant createdAt, boolean isPublic) {
            reviews.computeIfAbsent(memberId, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                    .add(new TimelineVisitReviewRecord(reviewId, placeId, placeName, createdAt, isPublic));
        }

        @Override
        public List<TimelineVisitReviewRecord> records(UUID memberId) {
            return reviews.getOrDefault(memberId, List.of());
        }
    }
}
