package com.yrootlab.onmaru.audio.query;

import java.util.List;
import java.util.UUID;

public record OdiiStoryReadSelection(
        UUID revisionId,
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiStoryProjection> stories) {

    public OdiiStoryReadSelection {
        stories = stories == null ? List.of() : List.copyOf(stories);
    }
}
