package com.yrootlab.onmaru.audio.query;

import java.util.List;

public record OdiiPopularSoundsPage(
        String schemaVersion,
        String basis,
        String window,
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiPopularSoundItem> items) {

    public OdiiPopularSoundsPage {
        items = List.copyOf(items);
    }
}
