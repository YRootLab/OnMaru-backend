package com.yrootlab.onmaru.tourism.catalog.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiSourceRecord;

import java.util.LinkedHashMap;
import java.util.Map;

public final class TourApiCatalogSourceMapper {

    private static final String PROVIDER = "kto-tourapi-korean";

    public SourceRecord toSourceRecord(TourApiSourceRecord item) {
        Map<String, String> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : item.fields().properties()) {
            JsonNode value = field.getValue();
            if (value == null || value.isNull()) {
                fields.put(field.getKey(), "");
            } else if (value.isValueNode()) {
                fields.put(field.getKey(), value.asText());
            } else {
                fields.put(field.getKey(), value.toString());
            }
        }
        return new SourceRecord(PROVIDER, item.operation(), fields);
    }
}
