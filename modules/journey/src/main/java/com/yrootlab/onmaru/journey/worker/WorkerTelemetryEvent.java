package com.yrootlab.onmaru.journey.worker;

import java.util.Map;

public record WorkerTelemetryEvent(String name, Map<String, String> attributes) {

    public WorkerTelemetryEvent {
        attributes = Map.copyOf(attributes);
    }
}
