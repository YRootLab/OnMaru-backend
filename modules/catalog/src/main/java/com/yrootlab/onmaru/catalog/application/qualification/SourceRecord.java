package com.yrootlab.onmaru.catalog.application.qualification;

import java.util.LinkedHashMap;
import java.util.Map;

public record SourceRecord(
        String provider,
        String operation,
        Map<String, String> fields
) {
    public SourceRecord {
        fields = Map.copyOf(new LinkedHashMap<>(fields));
    }

    public String field(String name) {
        return fields.get(name);
    }

    public String recordKey() {
        String externalId = field("contentid");
        if (externalId == null || externalId.isBlank()) {
            return provider + ":" + operation + ":missing-contentid";
        }
        return provider + ":" + operation + ":" + externalId;
    }
}
