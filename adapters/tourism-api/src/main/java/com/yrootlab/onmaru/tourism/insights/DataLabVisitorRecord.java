package com.yrootlab.onmaru.tourism.insights;

import java.time.LocalDate;

/** Raw provider observation. Provider codes intentionally remain unmapped to Catalog codes here. */
public record DataLabVisitorRecord(
        DataLabVisitorRequest.Scope scope,
        LocalDate basisDate,
        String providerRegionCode,
        String providerRegionName,
        String visitorDivisionCode,
        String visitorDivisionName,
        Long visitorCount
) {
}
