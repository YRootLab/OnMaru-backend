package com.yrootlab.onmaru.audio.query;

import java.util.List;
import java.util.Objects;

public record OdiiStoryDetail(
        String schemaVersion,
        OdiiCoverageStatus coverageStatus,
        String language,
        OdiiLanguageStatus languageStatus,
        OdiiStorySummary story,
        String audioUrl,
        OdiiTranscriptStatus transcriptStatus,
        List<OdiiTranscriptLine> transcript) {

    public OdiiStoryDetail {
        Objects.requireNonNull(transcriptStatus, "transcriptStatus");
        transcript = transcriptStatus == OdiiTranscriptStatus.MISSING || transcript == null
                ? List.of()
                : List.copyOf(transcript);
    }
}
