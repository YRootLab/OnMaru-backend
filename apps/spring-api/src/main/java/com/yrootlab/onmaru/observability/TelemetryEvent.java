package com.yrootlab.onmaru.observability;

import java.util.Map;

public record TelemetryEvent(String name, Map<String, String> attributes) {

    public TelemetryEvent {
        attributes = Map.copyOf(attributes);
    }
}
