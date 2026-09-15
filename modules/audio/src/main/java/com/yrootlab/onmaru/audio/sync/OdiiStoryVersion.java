package com.yrootlab.onmaru.audio.sync;

import java.time.Instant;

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
        String contentHash
) {
}
