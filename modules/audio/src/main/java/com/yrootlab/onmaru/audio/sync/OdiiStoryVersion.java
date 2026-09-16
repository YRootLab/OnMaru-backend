package com.yrootlab.onmaru.audio.sync;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;

public record OdiiStoryVersion(
        OdiiStoryIdentity identity,
        OdiiSpotIdentity spotIdentity,
        String title,
        String script,
        TranscriptProvenance transcriptProvenance,
        String audioUrl,
        String imageUrl,
        Integer durationSeconds,
        Instant sourceModifiedAt,
        AudioStatus status,
        String contentHash,
        List<AudioSubtitleLine> subtitleLines
) {

    public OdiiStoryVersion {
        subtitleLines = subtitleLines == null ? List.of() : List.copyOf(subtitleLines);
    }

    public OdiiStoryVersion(
            OdiiStoryIdentity identity,
            OdiiSpotIdentity spotIdentity,
            String title,
            String script,
            TranscriptProvenance transcriptProvenance,
            String audioUrl,
            String imageUrl,
            Integer durationSeconds,
            Instant sourceModifiedAt,
            AudioStatus status,
            String contentHash
    ) {
        this(
                identity,
                spotIdentity,
                title,
                script,
                transcriptProvenance,
                audioUrl,
                imageUrl,
                durationSeconds,
                sourceModifiedAt,
                status,
                contentHash,
                subtitleLines(script, transcriptProvenance));
    }

    private static List<AudioSubtitleLine> subtitleLines(
            String script,
            TranscriptProvenance provenance
    ) {
        if (script == null || script.isBlank() || provenance == TranscriptProvenance.MISSING) {
            return List.of();
        }
        String[] lines = script.split("\\R");
        return java.util.stream.IntStream.range(0, lines.length)
                .filter(index -> !lines[index].isBlank())
                .mapToObj(index -> new AudioSubtitleLine(
                        index,
                        BigDecimal.ZERO,
                        lines[index],
                        SubtitleTimingMode.OFFICIAL))
                .toList();
    }
}
