package com.yrootlab.onmaru.observability;

import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.journey.worker.WorkerTelemetryEvent;
import org.springframework.stereotype.Component;

@Component
final class SpringJourneyWorkerTelemetry implements JourneyWorkerTelemetry {

    private final TelemetrySink telemetrySink;

    SpringJourneyWorkerTelemetry(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @Override
    public void record(WorkerTelemetryEvent event) {
        telemetrySink.record(new TelemetryEvent(event.name(), event.attributes()));
    }
}
