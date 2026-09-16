package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.sync.ActiveAudioRevision;
import com.yrootlab.onmaru.audio.sync.AudioRevisionSnapshot;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStage;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.AudioStageCompletion;
import com.yrootlab.onmaru.audio.sync.InMemoryAudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiMappedStory;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.audio.sync.OdiiSourceStory;
import com.yrootlab.onmaru.catalog.application.publication.PublicationPlan;
import com.yrootlab.onmaru.catalog.application.publication.PublicationStatus;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import com.yrootlab.onmaru.catalog.application.publication.StageValidation;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActiveRevisionOdiiStoryQueryStoreTests {

    private static final String DATASET = "odii-audio";
    private static final UUID REVISION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

    @Test
    void mapsThePublishedRevisionSnapshotToThePublicQueryProjection() {
        var mapped = new OdiiSourceMapper().map(source());
        var store = new ActiveRevisionOdiiStoryQueryStore(revisionStore(
                AudioRevisionSnapshot.from(List.of(mapped))), DATASET);

        var active = store.activeSnapshot();

        assertThat(active.revisionId()).isEqualTo(REVISION_ID);
        assertThat(active.stories()).singleElement().satisfies(story -> {
            assertThat(story.storyId()).isEqualTo(publicId("odii-story-", "562"));
            assertThat(story.spotId()).isEqualTo(publicId("odii-spot-", "89"));
            assertThat(story.language()).isEqualTo("ko-KR");
            assertThat(story.title()).isEqualTo("전주 한옥마을");
            assertThat(story.audioTitle()).isEqualTo("한옥 골목 이야기");
            assertThat(story.category()).isEqualTo("오디오 관광");
            assertThat(story.region()).isEqualTo(new OdiiRegionRef("kr", "대한민국", "COUNTRY", null));
            assertThat(story.coordinates()).isEqualTo(new OdiiCoordinates(35.817632, 127.152948));
            assertThat(story.transcriptStatus()).isEqualTo(OdiiTranscriptStatus.OFFICIAL);
            assertThat(story.transcript()).extracting(OdiiTranscriptLine::text)
                    .containsExactly("골목에 남은 이야기를 들어보세요.");
        });
    }

    @Test
    void failsClosedWhenThePublishedRevisionContainsNoStories() {
        var store = new ActiveRevisionOdiiStoryQueryStore(
                revisionStore(AudioRevisionSnapshot.empty()), DATASET);

        assertThatThrownBy(store::activeSnapshot)
                .isInstanceOf(OdiiStoryUnavailableException.class);
    }

    @Test
    void keepsRevisionAndSnapshotFromTheSameAtomicPublicationRead() {
        var store = new ActiveRevisionOdiiStoryQueryStore(
                new PublishingBetweenReadsStore(
                        REVISION_ID,
                        snapshot("old title"),
                        snapshot("new title")),
                DATASET);

        var active = store.activeSnapshot();

        assertThat(active.revisionId()).isEqualTo(REVISION_ID);
        assertThat(active.stories()).singleElement()
                .extracting(OdiiStoryProjection::audioTitle)
                .isEqualTo("old title");
    }

    private InMemoryAudioRevisionStore revisionStore(AudioRevisionSnapshot snapshot) {
        return new InMemoryAudioRevisionStore(
                DATASET,
                REVISION_ID,
                snapshot,
                new SourceWatermark("2026-09-15T02:00:00Z", "562", Instant.parse("2026-09-15T03:00:00Z")),
                "worker-a",
                1);
    }

    private String publicId(String prefix, String providerId) {
        return prefix + UUID.nameUUIDFromBytes(
                ("KTO_ODII:" + prefix + ":" + providerId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private OdiiSourceStory source() {
        return source("한옥 골목 이야기");
    }

    private AudioRevisionSnapshot snapshot(String audioTitle) {
        return AudioRevisionSnapshot.from(List.of(new OdiiSourceMapper().map(source(audioTitle))));
    }

    private OdiiSourceStory source(String audioTitle) {
        return new OdiiSourceStory(
                "89",
                "300",
                "562",
                "1204",
                "전주 한옥마을",
                audioTitle,
                "골목에 남은 이야기를 들어보세요.",
                "https://cdn.onmaru.example/odii/story.mp3",
                "https://cdn.onmaru.example/odii/story.jpg",
                "185",
                "127.152948",
                "35.817632",
                "ko",
                "20150619173503",
                "20250609074606");
    }

    private static final class PublishingBetweenReadsStore implements AudioRevisionStore {
        private final UUID oldRevisionId;
        private final AudioRevisionSnapshot oldSnapshot;
        private final AudioRevisionSnapshot newSnapshot;
        private boolean published;

        private PublishingBetweenReadsStore(
                UUID oldRevisionId,
                AudioRevisionSnapshot oldSnapshot,
                AudioRevisionSnapshot newSnapshot
        ) {
            this.oldRevisionId = oldRevisionId;
            this.oldSnapshot = oldSnapshot;
            this.newSnapshot = newSnapshot;
        }

        @Override
        public ActiveAudioRevision activePublishedRevision(String dataset) {
            return new ActiveAudioRevision(oldRevisionId, oldSnapshot);
        }

        @Override
        public UUID activeRevision(String dataset) {
            published = true;
            return oldRevisionId;
        }

        @Override
        public AudioRevisionSnapshot activeSnapshot() {
            return published ? newSnapshot.copy() : oldSnapshot.copy();
        }

        @Override
        public AudioRevisionStage openStage(String dataset, UUID expectedBaseRevisionId, Instant observedAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void stage(UUID revisionId, List<OdiiMappedStory> stories) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AudioStageCompletion completeStage(
                UUID revisionId,
                int missingObservationThreshold,
                boolean emptyFullSyncReviewed
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void failStage(UUID revisionId, String failureCode) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long stagedItemCount(UUID revisionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StageValidation> stageValidation(UUID revisionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PublicationStatus publishIfLeaseAndBaseRevisionMatch(
                SyncRunLease lease,
                PublicationPlan plan,
                Instant publishedAt
        ) {
            throw new UnsupportedOperationException();
        }
    }
}
