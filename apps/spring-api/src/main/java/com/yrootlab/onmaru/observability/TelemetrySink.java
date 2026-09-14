package com.yrootlab.onmaru.observability;

interface TelemetrySink {

    void record(TelemetryEvent event);
}
