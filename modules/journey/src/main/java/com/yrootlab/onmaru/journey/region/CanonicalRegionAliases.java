package com.yrootlab.onmaru.journey.region;

import java.util.List;
import java.util.Map;

/**
 * Maps the service's canonical province codes (kr-NN) to the codes stored in the catalog (catalog_regions.code).
 *
 * <p>The catalog keeps DataLab-coded provinces (kr-datalab-NN). Those rows are not renamed: DataLab visitor ingestion
 * and the catalog migration invariants depend on them. This table is the only place where the two code systems meet.
 *
 * <p>Gwangju (kr-29) and Jeonnam (kr-46) are intentionally absent. They share the DataLab province kr-datalab-12, and
 * they can only be separated by the explicit sigungu codes of the 5 Gwangju districts. Until those codes are confirmed
 * from the catalog, requests for these two provinces are refused rather than guessed.
 */
public final class CanonicalRegionAliases {

    private static final Map<String, String> PROVINCE_TO_CATALOG = Map.ofEntries(
            Map.entry("kr-11", "kr-11"),
            Map.entry("kr-26", "kr-datalab-26"),
            Map.entry("kr-27", "kr-datalab-27"),
            Map.entry("kr-28", "kr-datalab-28"),
            Map.entry("kr-30", "kr-datalab-30"),
            Map.entry("kr-31", "kr-datalab-31"),
            Map.entry("kr-36", "kr-datalab-36"),
            Map.entry("kr-41", "kr-datalab-41"),
            Map.entry("kr-42", "kr-datalab-51"),
            Map.entry("kr-43", "kr-datalab-43"),
            Map.entry("kr-44", "kr-datalab-44"),
            Map.entry("kr-45", "kr-45"),
            Map.entry("kr-47", "kr-datalab-47"),
            Map.entry("kr-48", "kr-datalab-48"),
            Map.entry("kr-50", "kr-datalab-50"));

    private CanonicalRegionAliases() {
    }

    /**
     * Catalog codes that a canonical request code may match. A province maps to exactly one catalog code. A
     * sigungu-level code (for example kr-11-jongno) is returned as-is, because the catalog already uses that form.
     * Unmapped provinces return an empty list.
     */
    public static List<String> catalogCodesFor(String canonical) {
        if (canonical == null) {
            return List.of();
        }
        if (PROVINCE_TO_CATALOG.containsKey(canonical)) {
            return List.of(PROVINCE_TO_CATALOG.get(canonical));
        }
        if (canonical.matches("^kr-\\d{2}-[a-z0-9]+(?:-[a-z0-9]+)*$")) {
            return List.of(canonical);
        }
        return List.of();
    }

    /** Whether the province is covered by the alias table. */
    public static boolean isMappedProvince(String canonical) {
        return PROVINCE_TO_CATALOG.containsKey(canonical);
    }
}
