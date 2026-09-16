package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.tags.ContentTagQualityReport;

import java.time.Instant;
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
        List<String> contentTags,
        ContentTagQualityReport contentTagQualityReport,
        String contentHash
) {

    public OdiiStoryVersion {
        contentTags = contentTags == null ? List.of() : List.copyOf(contentTags);
        contentTagQualityReport = contentTagQualityReport == null
                ? new ContentTagQualityReport(
                0,
                contentTags.size(),
                0,
                0,
                0,
                contentTags.isEmpty(),
                contentTags.size() < 3,
                List.of())
                : contentTagQualityReport;
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
            List<String> contentTags,
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
                contentTags,
                null,
                contentHash);
    }
}
