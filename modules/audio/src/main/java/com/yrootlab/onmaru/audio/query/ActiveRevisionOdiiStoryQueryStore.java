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
    private final OdiiProjectionMetadataResolver metadataResolver;

    public ActiveRevisionOdiiStoryQueryStore(AudioRevisionStore revisionStore, String dataset) {
        this(revisionStore, dataset, ignored -> new OdiiProjectionMetadata(CATEGORY, COUNTRY_REGION));
    }

    public ActiveRevisionOdiiStoryQueryStore(
            AudioRevisionStore revisionStore,
            String dataset,
            OdiiProjectionMetadataResolver metadataResolver
    ) {
        this.revisionStore = Objects.requireNonNull(revisionStore, "revisionStore");
        if (dataset == null || dataset.isBlank()) {
            throw new IllegalArgumentException("dataset must not be blank");
        }
        this.dataset = dataset;
        this.metadataResolver = Objects.requireNonNull(metadataResolver, "metadataResolver");
    }

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        var activeRevision = revisionStore.activePublishedRevision(dataset);
        UUID revisionId = activeRevision.revisionId();
        var snapshot = activeRevision.snapshot();
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
                ? List.of()
                : story.subtitleLines().stream()
                        .map(line -> new OdiiTranscriptLine(
                                line.position(),
                                line.startSeconds() == null ? 0 : line.startSeconds().doubleValue(),
                                line.text()))
                        .toList();
        OdiiProjectionMetadata metadata = metadataResolver.resolve(spot);
        return new OdiiStoryProjection(
                publicId("odii-story-", story.identity().provider(), story.identity().stid()),
                publicId("odii-spot-", spot.identity().provider(), spot.identity().tid()),
                publicLanguage(story.identity().langCode()),
                spot.title(),
                story.title(),
                metadata.category(),
                metadata.region(),
                new OdiiCoordinates(spot.latitude().doubleValue(), spot.longitude().doubleValue()),
                story.durationSeconds(),
                story.imageUrl(),
                story.audioUrl(),
                transcriptStatus,
                transcript,
                story.contentTags(),
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
