package com.yrootlab.onmaru.journey.worker;

@FunctionalInterface
public interface JourneyWorkerTelemetry {

    void record(WorkerTelemetryEvent event);
}
