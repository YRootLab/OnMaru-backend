package com.yrootlab.onmaru.tourism.catalog.client;

import com.fasterxml.jackson.databind.JsonNode;

public record TourApiSourceRecord(
        String operation,
        JsonNode fields
) {
}
