package com.yrootlab.onmaru.audio.query;

import java.util.List;

public record OdiiRegionGroupsPage(
        String schemaVersion,
        String language,
        OdiiLanguageStatus languageStatus,
        List<OdiiRegionGroup> groups) {

    public OdiiRegionGroupsPage {
        groups = List.copyOf(groups);
    }
}
