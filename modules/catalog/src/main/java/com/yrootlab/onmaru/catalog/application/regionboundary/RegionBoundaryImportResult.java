package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.util.List;

public record RegionBoundaryImportResult(
        RegionBoundaryImportStatus status,
        String revisionId,
        int acceptedCount,
        List<RegionBoundaryQuarantine> quarantine
) {

    public RegionBoundaryImportResult {
        quarantine = List.copyOf(quarantine);
    }
}
