package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiRevisionSyncServiceTests {

    private static final String DATASET = "odii-audio";
    private static final Instant NOW = Instant.parse("2026-09-15T03:20:00Z");
    private static final SourceWatermark PREVIOUS_WATERMARK = new SourceWatermark(
            "2025-06-08T07:46:06Z", "1200", Instant.parse("2026-09-14T03:20:00Z"));

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final OdiiSourceMapper mapper = new OdiiSourceMapper();

    @Test
    void lastLanguagePageFailureLeavesPreviousLkgAndWatermarkUntouched() {
        UUID baseRevision = UUID.randomUUID();
        var baseStory = mapper.map(source("300", "1204", "ko", "562"));
        var store = store(baseRevision, List.of(baseStory));
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true))
                .page("en", 1, page(List.of(source("301", "2187", "en", "562")), false))
                .fail("en", 2);
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult result = service.sync(command(baseRevision, List.of("ko", "en"), 1));

        assertThat(result.status()).isEqualTo(OdiiSyncStatus.SOURCE_FAILED);
        assertThat(store.activeRevision(DATASET)).isEqualTo(baseRevision);
        assertThat(store.watermark()).isEqualTo(PREVIOUS_WATERMARK);
        assertThat(store.activeSnapshot().stories()).containsOnly(baseStory.story());
    }

    @Test
    void publishesAllLanguagesAndSpotStoriesAsOneRevision() {
        UUID baseRevision = UUID.randomUUID();
        var store = store(baseRevision, List.of());
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true))
                .page("en", 1, page(List.of(source("301", "2187", "en", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult result = service.sync(command(baseRevision, List.of("ko", "en"), 1));

        assertThat(result.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(store.activeRevision(DATASET)).isEqualTo(result.stagedRevisionId());
        assertThat(store.activeSnapshot().spots()).hasSize(2);
        assertThat(store.activeSnapshot().stories()).hasSize(2);
        assertThat(store.activeSnapshot().stories())
                .extracting(version -> version.identity().langCode())
                .containsExactlyInAnyOrder("ko", "en");
        assertThat(store.watermark().lastSuccessAt()).isEqualTo(NOW);
    }

    @Test
    void summarizesContentTagQualityForPublishedRevision() {
        UUID baseRevision = UUID.randomUUID();
        var store = store(baseRevision, List.of());
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(genericTagSource("300", "1204", "ko", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult result = service.sync(command(baseRevision, List.of("ko"), 1));

        assertThat(result.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(result.contentTagQuality().storyCount()).isEqualTo(1);
        assertThat(result.contentTagQuality().emptyStoryCount()).isEqualTo(1);
        assertThat(result.contentTagQuality().lowConfidenceStoryCount()).isEqualTo(1);
        assertThat(result.contentTagQuality().removedGenericCount()).isGreaterThan(0);
    }

    @Test
    void missingStoryAndSpotBecomeTombstonesOnlyAfterTwoSuccessfulFullRuns() {
        UUID baseRevision = UUID.randomUUID();
        var retained = mapper.map(source("300", "1204", "ko", "562"));
        var missing = mapper.map(source("412", "1520", "ko", "732"));
        var store = store(baseRevision, List.of(retained, missing));
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult first = service.sync(command(baseRevision, List.of("ko"), 1));
        assertThat(first.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(findStory(store, "1520").status()).isEqualTo(AudioStatus.ACTIVE);

        OdiiSyncResult second = service.sync(command(first.stagedRevisionId(), List.of("ko"), 1));
        assertThat(second.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(second.tombstoneCount()).isEqualTo(2);
        assertThat(findStory(store, "1520").status()).isEqualTo(AudioStatus.DELETED);
        assertThat(findSpot(store, "412").status()).isEqualTo(AudioStatus.DELETED);
    }

    @Test
    void staleLeaseOrBaseCannotReplacePublishedRevision() {
        UUID baseRevision = UUID.randomUUID();
        var store = store(baseRevision, List.of());
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult staleLease = service.sync(command(baseRevision, List.of("ko"), 0));
        OdiiSyncResult staleBase = service.sync(command(UUID.randomUUID(), List.of("ko"), 1));

        assertThat(staleLease.status()).isEqualTo(OdiiSyncStatus.LEASE_LOST);
        assertThat(staleBase.status()).isEqualTo(OdiiSyncStatus.ACTIVE_REVISION_CHANGED);
        assertThat(store.activeRevision(DATASET)).isEqualTo(baseRevision);
    }

    @Test
    void rejectedPublicationDoesNotAdvanceMissingObservationCounters() {
        UUID baseRevision = UUID.randomUUID();
        var retained = mapper.map(source("300", "1204", "ko", "562"));
        var missing = mapper.map(source("412", "1520", "ko", "732"));
        var store = store(baseRevision, List.of(retained, missing));
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock);

        OdiiSyncResult rejected = service.sync(command(baseRevision, List.of("ko"), 0));
        OdiiSyncResult accepted = service.sync(command(baseRevision, List.of("ko"), 1));

        assertThat(rejected.status()).isEqualTo(OdiiSyncStatus.LEASE_LOST);
        assertThat(accepted.status()).isEqualTo(OdiiSyncStatus.PUBLISHED);
        assertThat(accepted.tombstoneCount()).isZero();
        assertThat(findStory(store, "1520").status()).isEqualTo(AudioStatus.ACTIVE);
        assertThat(findSpot(store, "412").status()).isEqualTo(AudioStatus.ACTIVE);
    }

    @Test
    void recordsSyncLifecycleForPublishedAndSourceFailedRuns() {
        UUID baseRevision = UUID.randomUUID();
        var store = store(baseRevision, List.of());
        var observer = new RecordingObserver();
        var source = new StubPageSource()
                .page("ko", 1, page(List.of(source("300", "1204", "ko", "562")), true));
        var service = new OdiiRevisionSyncService(store, source, mapper, clock, observer);

        OdiiSyncResult published = service.sync(command(baseRevision, List.of("ko"), 1));

        assertThat(observer.events).containsExactly(
                "started:odii-audio:" + baseRevision,
                "completed:odii-audio:PUBLISHED:" + published.stagedRevisionId() + ":2:0");

        var failingObserver = new RecordingObserver();
        var failing = new OdiiRevisionSyncService(
                store,
                new StubPageSource().fail("ko", 1),
                mapper,
                clock,
                failingObserver);

        OdiiSyncResult failed = failing.sync(command(published.stagedRevisionId(), List.of("ko"), 1));

        assertThat(failingObserver.events).containsExactly(
                "started:odii-audio:" + published.stagedRevisionId(),
                "failed:odii-audio:" + failed.stagedRevisionId() + ":SOURCE_FAILED",
                "completed:odii-audio:SOURCE_FAILED:" + failed.stagedRevisionId() + ":0:0");
    }

    private InMemoryAudioRevisionStore store(UUID baseRevision, List<OdiiMappedStory> stories) {
        return new InMemoryAudioRevisionStore(
                DATASET,
                baseRevision,
                AudioRevisionSnapshot.from(stories),
                PREVIOUS_WATERMARK,
                "worker-a",
                1
        );
    }

    private OdiiSyncCommand command(UUID baseRevision, List<String> languages, int generation) {
        return new OdiiSyncCommand(
                DATASET,
                new SyncRunLease(UUID.randomUUID(), DATASET, "worker-a", generation),
                baseRevision,
                languages,
                false
        );
    }

    private OdiiSourcePage page(List<OdiiSourceStory> stories, boolean lastPage) {
        return new OdiiSourcePage(stories, lastPage);
    }

    private OdiiSourceStory source(String tlid, String stlid, String language, String stid) {
        return new OdiiSourceStory(
                tlid.equals("412") ? "146" : "89",
                tlid,
                stid,
                stlid,
                "story-" + stlid,
                "audio-" + stlid,
                "official script",
                "https://example.com/" + stlid + ".mp3",
                "",
                "105",
                "126.9936798",
                "37.559163",
                language,
                "20150619173503",
                "20250609074606"
        );
    }

    private OdiiSourceStory genericTagSource(String tlid, String stlid, String language, String stid) {
        return new OdiiSourceStory(
                "89",
                tlid,
                stid,
                stlid,
                "관광 정보 안내",
                "관광 소개 코스",
                "관광 정보 안내 소개 코스 여행",
                "https://example.com/" + stlid + ".mp3",
                "",
                "105",
                "126.9936798",
                "37.559163",
                language,
                "20150619173503",
                "20250609074606"
        );
    }

    private OdiiStoryVersion findStory(InMemoryAudioRevisionStore store, String stlid) {
        return store.activeSnapshot().stories().stream()
                .filter(version -> version.identity().stlid().equals(stlid))
                .findFirst()
                .orElseThrow();
    }

    private OdiiSpotVersion findSpot(InMemoryAudioRevisionStore store, String tlid) {
        return store.activeSnapshot().spots().stream()
                .filter(version -> version.identity().tlid().equals(tlid))
                .findFirst()
                .orElseThrow();
    }

    private static final class StubPageSource implements OdiiPageSource {

        private final Map<String, OdiiSourcePage> pages = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();

        StubPageSource page(String language, int page, OdiiSourcePage sourcePage) {
            pages.put(key(language, page), sourcePage);
            return this;
        }

        StubPageSource fail(String language, int page) {
            failures.add(key(language, page));
            return this;
        }

        @Override
        public OdiiSourcePage fetch(String language, String keyword, int page) {
            if (failures.contains(key(language, page))) {
                throw new OdiiSourceException("provider failure");
            }
            OdiiSourcePage sourcePage = pages.get(key(language, page));
            if (sourcePage == null) {
                throw new OdiiSourceException("missing stub page");
            }
            return sourcePage;
        }

        private String key(String language, int page) {
            return language + ":" + page;
        }
    }

    private static final class RecordingObserver implements OdiiSyncObserver {

        private final List<String> events = new ArrayList<>();

        @Override
        public void started(String dataset, UUID expectedRevisionId) {
            events.add("started:" + dataset + ":" + expectedRevisionId);
        }

        @Override
        public void failed(String dataset, UUID revisionId, String failureCode) {
            events.add("failed:" + dataset + ":" + revisionId + ":" + failureCode);
        }

        @Override
        public void completed(String dataset, OdiiSyncResult result) {
            events.add("completed:" + dataset + ":" + result.status() + ":" + result.stagedRevisionId()
                    + ":" + result.itemCount() + ":" + result.tombstoneCount());
        }
    }
}
