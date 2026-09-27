package com.yrootlab.onmaru.audio.query;

import java.util.List;

public record OdiiPopularReadSelection(
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiPopularReadCandidate> candidates,
        boolean hasSignal) {

    public OdiiPopularReadSelection {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }
}
