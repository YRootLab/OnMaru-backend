package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiSpotIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.audio.sync.OdiiStoryIdentity;
import com.yrootlab.onmaru.audio.sync.OdiiStoryVersion;
import com.yrootlab.onmaru.audio.sync.TranscriptProvenance;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ActiveRevisionOdiiStoryQueryStore implements OdiiStoryQueryStore {

    private static final OdiiRegionRef COUNTRY_REGION =
            new OdiiRegionRef("kr", "대한민국", "COUNTRY", null);
    private static final String CATEGORY = "오디오 관광";

    private final AudioRevisionStore revisionStore;
    private final String dataset;

    public ActiveRevisionOdiiStoryQueryStore(AudioRevisionStore revisionStore, String dataset) {
        this.revisionStore = Objects.requireNonNull(revisionStore, "revisionStore");
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset must not be blank");
        }
        this.dataset = dataset;
    }

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        UUID revisionId = revisionStore.activeRevision(dataset);
        var snapshot = revisionStore.activeSnapshot();
        if (revisionId == null || snapshot.stories().isEmpty()) {
            throw new OdiiStoryUnavailableException();
        }
        Map<OdiiSpotIdentity, OdiiSpotVersion> spots = snapshot.spots().stream()
                .collect(Collectors.toUnmodifiableMap(OdiiSpotVersion::identity, Function.identity()));
        List<OdiiStoryProjection> stories = snapshot.stories().stream()
                .map(story -> projection(story, spots.get(story.spotIdentity())))
                .filter(Objects::nonNull)
                .toList();
        if (stories.isEmpty()) {
            throw new OdiiStoryUnavailableException();
        }
        return new OdiiActiveSnapshot(revisionId, stories);
    }

    private OdiiStoryProjection projection(OdiiStoryVersion story, OdiiSpotVersion spot) {
        if (spot == null || spot.longitude() == null || spot.latitude() == null
                || story.sourceModifiedAt() == null) {
            return null;
        }
        OdiiTranscriptStatus transcriptStatus = story.transcriptProvenance() == TranscriptProvenance.OFFICIAL
                ? OdiiTranscriptStatus.OFFICIAL
                : OdiiTranscriptStatus.MISSING;
        List<OdiiTranscriptLine> transcript = transcriptStatus == OdiiTranscriptStatus.MISSING
                || story.script() == null || story.script().isBlank()
                ? List.of()
                : List.of(new OdiiTranscriptLine(0, 0, story.script()));
        return new OdiiStoryProjection(
                publicId("odii-story-", story.identity().provider(), story.identity().stid()),
                publicId("odii-spot-", spot.identity().provider(), spot.identity().tid()),
                publicLanguage(story.identity().langCode()),
                spot.title(),
                story.title(),
                CATEGORY,
                COUNTRY_REGION,
                new OdiiCoordinates(spot.latitude().doubleValue(), spot.longitude().doubleValue()),
                story.durationSeconds(),
                story.imageUrl(),
                story.audioUrl(),
                transcriptStatus,
                transcript,
                story.sourceModifiedAt(),
                story.status(),
                spot.status());
    }

    private String publicId(String prefix, String provider, String providerId) {
        String stableKey = provider + ":" + prefix + ":" + providerId;
        return prefix + UUID.nameUUIDFromBytes(stableKey.getBytes(StandardCharsets.UTF_8));
    }

    private String publicLanguage(String providerLanguage) {
        return switch (providerLanguage) {
            case "ko" -> "ko-KR";
            case "en" -> "en-US";
            case "ja" -> "ja-JP";
            case "zh-CN", "zh-TW" -> providerLanguage;
            default -> providerLanguage;
        };
    }
}
