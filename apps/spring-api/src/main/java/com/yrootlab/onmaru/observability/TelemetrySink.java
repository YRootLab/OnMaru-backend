package com.yrootlab.onmaru.observability;

public interface TelemetrySink {

    void record(TelemetryEvent event);
}
