package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.placelink.ApprovedAudioPlaceLink;
import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkMatchMethod;
import com.yrootlab.onmaru.audio.placelink.CanonicalPlaceLinkCard;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdiiStoryQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final UUID REVISION_ONE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID REVISION_TWO = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private InMemoryOdiiStoryQueryStore store;
    private TestOdiiStoryCursorCodec cursorCodec;
    private OdiiStoryQueryService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryOdiiStoryQueryStore();
        cursorCodec = new TestOdiiStoryCursorCodec();
        service = new OdiiStoryQueryService(
                store,
                (memberId, storyId) -> memberId.filter(MEMBER_ID::equals).isPresent()
                        && storyId.equals("odii-story-jeonju-hanok-01"),
                (spotId, memberId) -> spotId.equals("odii-spot-jeonju")
                        ? Optional.of(approvedPlaceLink(spotId))
                        : Optional.empty(),
                new OdiiPublicAudioUrlPolicy(Set.of("cdn.onmaru.example")),
                cursorCodec);
    }

    @Test
    void listsOnlyActiveRequestedLanguageWithFiltersSavedStateAndStableCursor() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-jeonju-hanok-01", "en-US", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-gyeongju-01", "ko-KR", "궁궐/역사", "kr-47-gyeongju",
                        Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-jeonju-hidden-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T03:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.HIDDEN)));

        var first = service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 1, null, Optional.of(MEMBER_ID)));
        var second = service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 1, first.nextCursor(), Optional.empty()));
        var filtered = service.list(new OdiiStoryQuery(
                "ko-KR", "한옥/고택", "kr-45-jeonju", 20, null, Optional.empty()));

        assertThat(first.schemaVersion()).isEqualTo("1.2");
        assertThat(first.coverageStatus()).isEqualTo(OdiiCoverageStatus.COMPLETE);
        assertThat(first.language()).isEqualTo("ko-KR");
        assertThat(first.languageStatus()).isEqualTo(OdiiLanguageStatus.EXACT);
        assertThat(first.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-jeonju-hanok-01");
        assertThat(first.items().getFirst().contentTags()).contains("한옥 골목");
        assertThat(first.items().getFirst().savedByMe()).isTrue();
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        assertThat(second.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-gyeongju-01");
        assertThat(second.hasMore()).isFalse();
        assertThat(filtered.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-jeonju-hanok-01");
    }

    @Test
    void searchesStoryTitleAudioTitleAndContentTagsWithoutExposingInactiveStories() {
        store.replaceActive(snapshot(REVISION_ONE,
                storyWithText("odii-story-palace-01", "경복궁의 궁궐 이야기", "왕실 문화 산책",
                        List.of("궁궐", "왕실"), AudioStatus.ACTIVE),
                storyWithText("odii-story-market-01", "남대문 시장 이야기", "전통시장 골목",
                        List.of("시장"), AudioStatus.ACTIVE),
                storyWithText("odii-story-hidden-01", "궁궐 비공개 이야기", "왕실 문화",
                        List.of("궁궐"), AudioStatus.HIDDEN)));

        var result = service.search("  왕실 ", "ko-KR", 20, Optional.empty());

        assertThat(result.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-palace-01");
        assertThat(result.hasMore()).isFalse();
    }

    @Test
    void listsNearbyStoriesByDistanceAndValidatesCoordinatesAndRadius() {
        var near = storyWithCoordinates("odii-story-near-01", 37.5665, 126.9780,
                Instant.parse("2026-09-15T01:00:00Z"));
        var far = storyWithCoordinates("odii-story-far-01", 35.1796, 129.0756,
                Instant.parse("2026-09-15T02:00:00Z"));
        store.replaceActive(snapshot(REVISION_ONE, far, near));

        var result = service.nearby(37.5665, 126.9780, 5_000, "ko-KR", 20, Optional.empty());

        assertThat(result.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-near-01");
        assertThatThrownBy(() -> service.nearby(91, 126.978, 5_000, "ko-KR", 20, Optional.empty()))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("lat");
        assertThatThrownBy(() -> service.nearby(37.5, 126.978, 0, "ko-KR", 20, Optional.empty()))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("radius");
    }

    @Test
    void recommendsKeywordMatchesBeforeRecentUnmatchedStoriesWithStableOrder() {
        store.replaceActive(snapshot(REVISION_ONE,
                storyWithTextAndPublishedAt("odii-story-match", "궁궐 이야기", "왕실 산책",
                        List.of("궁궐"), Instant.parse("2026-09-10T00:00:00Z")),
                storyWithTextAndPublishedAt("odii-story-recent", "시장 이야기", "전통시장",
                        List.of("시장"), Instant.parse("2026-09-15T00:00:00Z"))));

        var result = service.recommend("궁궐", "ko-KR", 20, Optional.empty());

        assertThat(result.items()).extracting(OdiiStorySummary::storyId)
                .containsExactly("odii-story-match");
    }

    @Test
    void fallsBackToKoreanWhenRequestedLanguageHasNoPublicStories() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));

        var page = service.list(OdiiStoryQuery.firstPage("en-US", 20, Optional.empty()));
        var detail = service.detail("odii-story-jeonju-hanok-01", "en-US", Optional.empty());

        assertThat(page.coverageStatus()).isEqualTo(OdiiCoverageStatus.PARTIAL);
        assertThat(page.language()).isEqualTo("ko-KR");
        assertThat(page.languageStatus()).isEqualTo(OdiiLanguageStatus.FALLBACK);
        assertThat(detail.language()).isEqualTo("ko-KR");
        assertThat(detail.languageStatus()).isEqualTo(OdiiLanguageStatus.FALLBACK);
    }

    @Test
    void usesPublishedStoryContentTagsBeforeRuntimeExtraction() {
        var published = story("odii-story-jeonju-hanok-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)
                .withContentTags(List.of("검수 태그", "저장 태그"));
        store.replaceActive(snapshot(REVISION_ONE, published));

        var page = service.list(OdiiStoryQuery.firstPage("ko-KR", 20, Optional.empty()));
        var detail = service.detail("odii-story-jeonju-hanok-01", "ko-KR", Optional.empty());

        assertThat(page.items().getFirst().contentTags()).containsExactly("검수 태그", "저장 태그");
        assertThat(detail.story().contentTags()).containsExactly("검수 태그", "저장 태그");
    }

    @Test
    void preservesOfficialEstimatedAndMissingTranscriptStatus() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-official-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T03:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-estimated-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.ESTIMATED, AudioStatus.ACTIVE),
                story("odii-story-missing-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.MISSING, AudioStatus.ACTIVE)));

        var official = service.detail("odii-story-official-01", "ko-KR", Optional.empty());
        var estimated = service.detail("odii-story-estimated-01", "ko-KR", Optional.empty());
        var missing = service.detail("odii-story-missing-01", "ko-KR", Optional.empty());

        assertThat(official.coverageStatus()).isEqualTo(OdiiCoverageStatus.COMPLETE);
        assertThat(official.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.OFFICIAL);
        assertThat(official.transcript()).hasSize(2);
        assertThat(official.story().contentTags()).contains("한옥 골목");
        assertThat(estimated.coverageStatus()).isEqualTo(OdiiCoverageStatus.PARTIAL);
        assertThat(estimated.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.ESTIMATED);
        assertThat(estimated.transcript()).hasSize(2);
        assertThat(missing.coverageStatus()).isEqualTo(OdiiCoverageStatus.MISSING);
        assertThat(missing.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.MISSING);
        assertThat(missing.transcript()).isEmpty();
    }

    @Test
    void resolvesOnlyApprovedCanonicalPlaceLinksThroughTheOptionalPort() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-jeonju-hanok-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T03:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-unlinked-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));

        var page = service.list(OdiiStoryQuery.firstPage("ko-KR", 20, Optional.empty()));

        assertThat(page.items()).extracting(OdiiStorySummary::linkedPlaceId)
                .containsExactly("p-jeonju-hanok-village", null);
    }

    @Test
    void returnsNotFoundForHiddenDeletedUnknownAndUnsafeAudioStories() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-hidden-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T03:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.HIDDEN),
                story("odii-story-deleted-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.DELETED),
                story("odii-story-unsafe-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)
                        .withAudioUrl("https://provider.example/audio.mp3?serviceKey=secret"),
                story("odii-story-hidden-spot-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T00:00:00Z"), OdiiTranscriptStatus.OFFICIAL,
                        AudioStatus.ACTIVE, AudioStatus.HIDDEN)));

        assertThatThrownBy(() -> service.detail("odii-story-hidden-01", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryNotFoundException.class);
        assertThatThrownBy(() -> service.detail("odii-story-deleted-01", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryNotFoundException.class);
        assertThatThrownBy(() -> service.detail("odii-story-unknown-01", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryNotFoundException.class);
        assertThatThrownBy(() -> service.detail("odii-story-unsafe-01", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryNotFoundException.class);
        assertThatThrownBy(() -> service.detail("odii-story-hidden-spot-01", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryNotFoundException.class);
        assertThat(service.list(OdiiStoryQuery.firstPage("ko-KR", 20, Optional.empty())).items())
                .isEmpty();
    }

    @Test
    void redactsUnsafeImageUrlsFromListAndDetailWithoutHidingPlayableStories() {
        var unsafeImage = storyWithImageUrl(
                story("odii-story-jeonju-hanok-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T03:00:00Z"),
                        OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                "https://provider.example/cover.jpg?serviceKey=secret");
        store.replaceActive(snapshot(REVISION_ONE, unsafeImage));

        var page = service.list(OdiiStoryQuery.firstPage("ko-KR", 20, Optional.empty()));
        var detail = service.detail("odii-story-jeonju-hanok-01", "ko-KR", Optional.empty());

        assertThat(page.items()).singleElement().extracting(OdiiStorySummary::imageUrl).isNull();
        assertThat(detail.story().imageUrl()).isNull();
        assertThat(detail.audioUrl()).isEqualTo(unsafeImage.audioUrl());
    }

    @Test
    void bindsCursorToLanguageFiltersLimitAndActiveRevision() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-first-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-second-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));
        var query = new OdiiStoryQuery("ko-KR", null, null, 1, null, Optional.empty());
        var cursor = service.list(query).nextCursor();

        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "en-US", null, null, 1, cursor, Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", "한옥/고택", null, 1, cursor, Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, "kr-45-jeonju", 1, cursor, Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 2, cursor, Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 1, cursor + "tampered", Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
    }

    @Test
    void removesStaleTranscriptLinesWhenStatusIsMissing() {
        var missing = story("odii-story-missing-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.MISSING, AudioStatus.ACTIVE);
        store.replaceActive(snapshot(REVISION_ONE, storyWithTranscript(
                missing,
                List.of(new OdiiTranscriptLine(0, 0, "노출되면 안 되는 stale 대본")))));

        var detail = service.detail("odii-story-missing-01", "ko-KR", Optional.empty());

        assertThat(detail.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.MISSING);
        assertThat(detail.transcript()).isEmpty();
    }

    @Test
    void expiresCursorWhenActiveRevisionChangesAndRejectsMalformedCursor() {
        store.replaceActive(snapshot(REVISION_ONE,
                story("odii-story-first-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T02:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE),
                story("odii-story-second-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T01:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));
        var cursor = service.list(OdiiStoryQuery.firstPage("ko-KR", 1, Optional.empty())).nextCursor();

        store.replaceActive(snapshot(REVISION_TWO,
                story("odii-story-third-01", "ko-KR", "한옥/고택", "kr-45-jeonju",
                        Instant.parse("2026-09-15T04:00:00Z"), OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE)));

        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 1, cursor, Optional.empty())))
                .isInstanceOf(OdiiCursorExpiredException.class);
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 20, "tampered", Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
    }

    @Test
    void groupsActiveStoriesByBroadRegionInCatalogOrder() {
        store.replaceActive(snapshot(REVISION_ONE,
                storyInRegion("odii-story-jeonju-hanok-01", "ko-KR", "kr-45-jeonju", "kr-45", "전북 전주시",
                        Instant.parse("2026-09-15T02:00:00Z")),
                storyInRegion("odii-story-gyeongju-01", "ko-KR", "kr-47-gyeongju", "kr-47", "경북 경주시",
                        Instant.parse("2026-09-15T01:00:00Z")),
                storyInRegion("odii-story-bukchon-01", "ko-KR", "kr-11-jongno", "kr-11", "서울 종로구",
                        Instant.parse("2026-09-15T03:00:00Z")),
                storyInRegion("odii-story-jeonju-hanok-01", "en-US", "kr-45-jeonju", "kr-45", "전북 전주시",
                        Instant.parse("2026-09-15T02:00:00Z"))));

        var page = service.regionGroups("ko-KR");

        assertThat(page.schemaVersion()).isEqualTo("1.2");
        assertThat(page.language()).isEqualTo("ko-KR");
        assertThat(page.languageStatus()).isEqualTo(OdiiLanguageStatus.EXACT);
        // 숨김 스토리·영어 중복은 카운트에서 제외되고, 카탈로그 선언 순서를 유지한다.
        assertThat(page.groups())
                .extracting(OdiiRegionGroup::label)
                .containsExactly("서울·경기·인천", "경북·대구", "전북");
        assertThat(page.groups())
                .filteredOn(group -> group.label().equals("서울·경기·인천"))
                .first()
                .satisfies(group -> {
                    assertThat(group.storyCount()).isEqualTo(1);
                    assertThat(group.regionCodes()).containsExactly("kr-11");
                });
        assertThat(page.groups())
                .filteredOn(group -> group.label().equals("전북"))
                .first()
                .satisfies(group -> {
                    assertThat(group.storyCount()).isEqualTo(1);
                    assertThat(group.regionCodes()).containsExactly("kr-45");
                });
    }

    @Test
    void groupsFallBackToKoreanCountsWhenRequestedLanguageIsMissing() {
        store.replaceActive(snapshot(REVISION_ONE,
                storyInRegion("odii-story-jeju-01", "ko-KR", "kr-50-jeju", "kr-50", "제주 제주시",
                        Instant.parse("2026-09-15T02:00:00Z"))));

        var page = service.regionGroups("fr-FR");

        assertThat(page.language()).isEqualTo("ko-KR");
        assertThat(page.languageStatus()).isEqualTo(OdiiLanguageStatus.FALLBACK);
        assertThat(page.groups())
                .extracting(OdiiRegionGroup::label, OdiiRegionGroup::storyCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("제주", 1L));
    }

    @Test
    void ranksPopularSoundsByScoreAndFallsBackToRecencyWithoutSignals() {
        var counter = new InMemoryOdiiStoryPopularityCounter();
        var rankedService = new OdiiStoryQueryService(
                store,
                (memberId, storyId) -> false,
                (spotId, memberId) -> Optional.empty(),
                new OdiiPublicAudioUrlPolicy(Set.of("cdn.onmaru.example")),
                cursorCodec,
                ContentTagPipeline.defaultPipeline(),
                counter);

        store.replaceActive(snapshot(REVISION_ONE,
                storyInRegion("odii-story-old-a", "ko-KR", "kr-45-jeonju", "kr-45", "전북 전주시",
                        Instant.parse("2026-09-10T00:00:00Z")),
                storyInRegion("odii-story-old-b", "ko-KR", "kr-45-jeonju", "kr-45", "전북 전주시",
                        Instant.parse("2026-09-11T00:00:00Z")),
                storyInRegion("odii-story-old-c", "ko-KR", "kr-45-jeonju", "kr-45", "전북 전주시",
                        Instant.parse("2026-09-12T00:00:00Z"))));

        var coldStart = rankedService.popular(new OdiiPopularSoundsQuery(
                "ko-KR", null, 2, Instant.EPOCH, Optional.empty()));
        assertThat(coldStart.basis()).isEqualTo("FALLBACK_RECENT");
        assertThat(coldStart.items())
                .extracting(item -> item.story().storyId())
                .containsExactly("odii-story-old-c", "odii-story-old-b");
        assertThat(coldStart.items()).allSatisfy(item -> {
            assertThat(item.score()).isZero();
            assertThat(item.playCount()).isZero();
            assertThat(item.saveCount()).isZero();
        });

        // old-b: 재생 2회 → 점수 4, old-a: 저장 1회 → 점수 1. 점수 순으로 재정렬된다.
        counter.recordPlay("odii-story-old-b", Instant.parse("2026-09-15T10:00:00Z"));
        counter.recordPlay("odii-story-old-b", Instant.parse("2026-09-15T11:00:00Z"));
        counter.recordSave("odii-story-old-a", Instant.parse("2026-09-15T12:00:00Z"));

        var popular = rankedService.popular(new OdiiPopularSoundsQuery(
                "ko-KR", null, 2,
                Instant.parse("2026-09-14T00:00:00Z"), Optional.empty()));
        assertThat(popular.basis()).isEqualTo("POPULARITY");
        assertThat(popular.items())
                .extracting(OdiiPopularSoundItem::rank, OdiiPopularSoundItem::score)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, 4L),
                        org.assertj.core.groups.Tuple.tuple(2, 1L));
        assertThat(popular.items())
                .extracting(item -> item.story().storyId())
                .containsExactly("odii-story-old-b", "odii-story-old-a");
    }

    private OdiiStoryProjection storyInRegion(
            String storyId,
            String language,
            String regionCode,
            String parentRegionCode,
            String regionName,
            Instant publishedAt) {
        return new OdiiStoryProjection(
                storyId,
                "odii-spot-" + storyId,
                language,
                "전주의 한옥 골목 이야기",
                "전주 한옥마을 산책",
                "한옥/고택",
                new OdiiRegionRef(regionCode, regionName, "CITY", parentRegionCode),
                new OdiiCoordinates(35.817632, 127.152948),
                185,
                "https://cdn.onmaru.example/odii/" + storyId + ".jpg",
                "https://cdn.onmaru.example/odii/" + storyId + ".mp3",
                OdiiTranscriptStatus.OFFICIAL,
                List.of(new OdiiTranscriptLine(0, 0, "첫 번째 대본입니다.")),
                List.of(),
                publishedAt,
                AudioStatus.ACTIVE,
                AudioStatus.ACTIVE);
    }

    private void rejectsInvalidQueryAndPropagatesUnavailableSnapshot() {
        store.replaceActive(snapshot(REVISION_ONE));

        assertThatThrownBy(() -> service.list(OdiiStoryQuery.firstPage("english", 20, Optional.empty())))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("language");
        assertThatThrownBy(() -> service.list(OdiiStoryQuery.firstPage("ko-KR", 51, Optional.empty())))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", "가".repeat(81), null, 20, null, Optional.empty())))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("category");
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, "r".repeat(33), 20, null, Optional.empty())))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("regionCode");
        assertThatThrownBy(() -> service.list(new OdiiStoryQuery(
                "ko-KR", null, null, 20, "c".repeat(513), Optional.empty())))
                .isInstanceOf(OdiiCursorInvalidException.class);
        assertThatThrownBy(() -> service.detail("provider-raw-123", "ko-KR", Optional.empty()))
                .isInstanceOf(OdiiStoryInvalidRequestException.class)
                .hasMessageContaining("storyId");

        store.markUnavailable();

        assertThatThrownBy(() -> service.list(OdiiStoryQuery.firstPage("ko-KR", 20, Optional.empty())))
                .isInstanceOf(OdiiStoryUnavailableException.class);
    }

    private OdiiActiveSnapshot snapshot(UUID revisionId, OdiiStoryProjection... stories) {
        return new OdiiActiveSnapshot(revisionId, List.of(stories));
    }

    private ApprovedAudioPlaceLink approvedPlaceLink(String spotId) {
        return new ApprovedAudioPlaceLink(
                spotId,
                AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                BigDecimal.ONE,
                Instant.parse("2026-09-15T00:00:00Z"),
                new CanonicalPlaceLinkCard(
                        "p-jeonju-hanok-village",
                        "전주 한옥마을",
                        "한옥/고택",
                        "전북 전주시",
                        "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                        false));
    }

    private OdiiStoryProjection story(
            String storyId,
            String language,
            String category,
            String regionCode,
            Instant publishedAt,
            OdiiTranscriptStatus transcriptStatus,
            AudioStatus status) {
        return story(storyId, language, category, regionCode, publishedAt, transcriptStatus, status, AudioStatus.ACTIVE);
    }

    private OdiiStoryProjection story(
            String storyId,
            String language,
            String category,
            String regionCode,
            Instant publishedAt,
            OdiiTranscriptStatus transcriptStatus,
            AudioStatus storyStatus,
            AudioStatus spotStatus) {
        List<OdiiTranscriptLine> transcript = transcriptStatus == OdiiTranscriptStatus.MISSING
                ? List.of()
                : List.of(
                        new OdiiTranscriptLine(0, 0, "첫 번째 대본입니다."),
                        new OdiiTranscriptLine(1, 12.5, "두 번째 대본입니다."));
        return new OdiiStoryProjection(
                storyId,
                storyId.equals("odii-story-jeonju-hanok-01")
                        ? "odii-spot-jeonju"
                        : "odii-spot-" + storyId,
                language,
                "전주의 한옥 골목 이야기",
                "전주 한옥마을 산책",
                category,
                new OdiiRegionRef(regionCode, "전북 전주시", "CITY", "kr-45"),
                new OdiiCoordinates(35.817632, 127.152948),
                185,
                "https://cdn.onmaru.example/odii/" + storyId + ".jpg",
                "https://cdn.onmaru.example/odii/" + storyId + ".mp3",
                transcriptStatus,
                transcript,
                List.of(),
                publishedAt,
                storyStatus,
                spotStatus);
    }

    private OdiiStoryProjection storyWithText(
            String storyId,
            String title,
            String audioTitle,
            List<String> contentTags,
            AudioStatus status) {
        var base = storyWithTextAndPublishedAt(
                storyId,
                title,
                audioTitle,
                contentTags,
                Instant.parse("2026-09-15T00:00:00Z"));
        return new OdiiStoryProjection(
                base.storyId(), base.spotId(), base.language(), base.title(), base.audioTitle(), base.category(),
                base.region(), base.coordinates(), base.durationSeconds(), base.imageUrl(), base.audioUrl(),
                base.transcriptStatus(), base.transcript(), base.contentTags(), base.publishedAt(), status,
                base.spotStatus());
    }

    private OdiiStoryProjection storyWithTextAndPublishedAt(
            String storyId,
            String title,
            String audioTitle,
            List<String> contentTags,
            Instant publishedAt) {
        var base = story(storyId, "ko-KR", "문화", "kr-11-seoul", publishedAt,
                OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE);
        return new OdiiStoryProjection(
                base.storyId(), base.spotId(), base.language(), title, audioTitle, base.category(),
                base.region(), base.coordinates(), base.durationSeconds(), base.imageUrl(), base.audioUrl(),
                base.transcriptStatus(), base.transcript(), contentTags, base.publishedAt(),
                base.status(), base.spotStatus());
    }

    private OdiiStoryProjection storyWithCoordinates(
            String storyId,
            double latitude,
            double longitude,
            Instant publishedAt) {
        var base = story(storyId, "ko-KR", "문화", "kr-11-seoul", publishedAt,
                OdiiTranscriptStatus.OFFICIAL, AudioStatus.ACTIVE);
        return new OdiiStoryProjection(
                base.storyId(), base.spotId(), base.language(), base.title(), base.audioTitle(), base.category(),
                base.region(), new OdiiCoordinates(latitude, longitude), base.durationSeconds(), base.imageUrl(),
                base.audioUrl(), base.transcriptStatus(), base.transcript(), base.contentTags(), base.publishedAt(),
                base.status(), base.spotStatus());
    }

    private OdiiStoryProjection storyWithTranscript(
            OdiiStoryProjection story,
            List<OdiiTranscriptLine> transcript) {
        return new OdiiStoryProjection(
                story.storyId(),
                story.spotId(),
                story.language(),
                story.title(),
                story.audioTitle(),
                story.category(),
                story.region(),
                story.coordinates(),
                story.durationSeconds(),
                story.imageUrl(),
                story.audioUrl(),
                story.transcriptStatus(),
                transcript,
                story.contentTags(),
                story.publishedAt(),
                story.status(),
                story.spotStatus());
    }

    private OdiiStoryProjection storyWithImageUrl(OdiiStoryProjection story, String imageUrl) {
        return new OdiiStoryProjection(
                story.storyId(),
                story.spotId(),
                story.language(),
                story.title(),
                story.audioTitle(),
                story.category(),
                story.region(),
                story.coordinates(),
                story.durationSeconds(),
                imageUrl,
                story.audioUrl(),
                story.transcriptStatus(),
                story.transcript(),
                story.contentTags(),
                story.publishedAt(),
                story.status(),
                story.spotStatus());
    }

    private static final class TestOdiiStoryCursorCodec implements OdiiStoryCursorCodec {

        private final Map<String, OdiiStoryCursor> cursors = new LinkedHashMap<>();

        @Override
        public String encode(OdiiStoryCursor cursor) {
            var token = "test-odii-cursor-" + cursors.size();
            cursors.put(token, cursor);
            return token;
        }

        @Override
        public OdiiStoryCursor decode(String cursor) {
            var decoded = cursors.get(cursor);
            if (decoded == null) {
                throw new OdiiCursorInvalidException();
            }
            return decoded;
        }
    }
}
