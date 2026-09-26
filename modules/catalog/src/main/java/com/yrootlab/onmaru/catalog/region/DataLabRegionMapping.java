package com.yrootlab.onmaru.catalog.region;

import java.time.Instant;

/** Auditable Catalog-to-DataLab region mapping. */
public record DataLabRegionMapping(
        String internalRegionCode,
        String dataLabRegionCode,
        Level level,
        String name,
        String sourceUrl,
        Instant sourceObservedAt,
        String verifiedBy,
        Instant verifiedAt,
        DataLabRegionMappingStatus status) {

    public DataLabRegionMapping {
        requireText(internalRegionCode, "internalRegionCode");
        requireText(dataLabRegionCode, "dataLabRegionCode");
        requireText(name, "name");
        if (level == null || status == null) {
            throw new IllegalArgumentException("level and status are required");
        }
        if (!dataLabRegionCode.startsWith(level.name() + ":")
                || dataLabRegionCode.length() == level.name().length() + 1) {
            throw new IllegalArgumentException("dataLabRegionCode must match its level");
        }
        if (status == DataLabRegionMappingStatus.ACTIVE
                && (isBlank(sourceUrl) || sourceObservedAt == null || isBlank(verifiedBy) || verifiedAt == null)) {
            throw new IllegalArgumentException("ACTIVE DataLab mapping requires complete provenance");
        }
        if (sourceUrl != null && !sourceUrl.matches("^https://\\S+$")) {
            throw new IllegalArgumentException("sourceUrl must be HTTPS");
        }
        if (verifiedBy != null && verifiedBy.isBlank()) {
            throw new IllegalArgumentException("verifiedBy must not be blank");
        }
    }

    private static void requireText(String value, String field) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum Level {
        SIDO,
        SIGUNGU
    }
}
