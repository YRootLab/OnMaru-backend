package com.yrootlab.onmaru.catalog.application.tags;

import java.util.List;

public record ContentTagQualityReport(
        int generatedCount,
        int finalCount,
        int removedGenericCount,
        int hiddenOverrideCount,
        int pinnedOverrideCount,
        boolean emptyResult,
        boolean lowConfidence,
        List<String> warnings
) {

    public ContentTagQualityReport {
        if (generatedCount < 0 || finalCount < 0 || removedGenericCount < 0
                || hiddenOverrideCount < 0 || pinnedOverrideCount < 0) {
            throw new IllegalArgumentException("quality counts must not be negative");
        }
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
