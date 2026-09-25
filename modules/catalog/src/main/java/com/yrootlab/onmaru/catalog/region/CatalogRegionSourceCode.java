package com.yrootlab.onmaru.catalog.region;

/** A currently valid provider-specific code for an active Catalog region. */
public record CatalogRegionSourceCode(String regionCode, String sourceCode) {

    public CatalogRegionSourceCode {
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode must not be blank");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new IllegalArgumentException("sourceCode must not be blank");
        }
    }
}
