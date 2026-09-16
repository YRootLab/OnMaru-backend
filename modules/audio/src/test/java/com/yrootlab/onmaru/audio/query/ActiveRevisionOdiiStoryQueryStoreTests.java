package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.sync.AudioRevisionSnapshot;
import com.yrootlab.onmaru.audio.sync.InMemoryAudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.audio.sync.OdiiSourceStory;
import com.yrootlab.onmaru.catalog.application.publication.SourceWatermark;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
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
        return new OdiiSourceStory(
                "89",
                "300",
                "562",
                "1204",
                "전주 한옥마을",
                "한옥 골목 이야기",
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
}
