package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.sync.AudioStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OdiiStoryProjection(
        String storyId,
        String spotId,
        String language,
        String title,
        String audioTitle,
        String category,
        OdiiRegionRef region,
        OdiiCoordinates coordinates,
        Integer durationSeconds,
        String imageUrl,
        String audioUrl,
        OdiiTranscriptStatus transcriptStatus,
        List<OdiiTranscriptLine> transcript,
        List<String> contentTags,
        Instant publishedAt,
        AudioStatus status,
        AudioStatus spotStatus) {

    public OdiiStoryProjection {
        Objects.requireNonNull(transcriptStatus, "transcriptStatus");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(spotStatus, "spotStatus");
        transcript = transcriptStatus == OdiiTranscriptStatus.MISSING || transcript == null
                ? List.of()
                : List.copyOf(transcript);
        contentTags = contentTags == null ? List.of() : List.copyOf(contentTags);
    }

    public OdiiStoryProjection withAudioUrl(String replacementAudioUrl) {
        return new OdiiStoryProjection(
                storyId,
                spotId,
                language,
                title,
                audioTitle,
                category,
                region,
                coordinates,
                durationSeconds,
                imageUrl,
                replacementAudioUrl,
                transcriptStatus,
                transcript,
                contentTags,
                publishedAt,
                status,
                spotStatus);
    }

    public OdiiStoryProjection withContentTags(List<String> replacementContentTags) {
        return new OdiiStoryProjection(
                storyId,
                spotId,
                language,
                title,
                audioTitle,
                category,
                region,
                coordinates,
                durationSeconds,
                imageUrl,
                audioUrl,
                transcriptStatus,
                transcript,
                replacementContentTags,
                publishedAt,
                status,
                spotStatus);
    }
}
