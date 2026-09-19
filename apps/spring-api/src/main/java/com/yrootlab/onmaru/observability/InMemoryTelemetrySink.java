package com.yrootlab.onmaru.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class InMemoryTelemetrySink implements TelemetrySink {

    private final CopyOnWriteArrayList<TelemetryEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(TelemetryEvent event) {
        events.add(event);
    }

    public List<TelemetryEvent> events() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}
