package com.yrootlab.onmaru.observability;

import com.yrootlab.onmaru.audio.query.OdiiActiveSnapshot;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryObserver;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
class TelemetryOdiiStoryQueryObserver implements OdiiStoryQueryObserver {

    private final TelemetrySink telemetrySink;

    TelemetryOdiiStoryQueryObserver(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @Override
    public void activeRevisionAvailable(OdiiActiveSnapshot snapshot) {
        telemetrySink.record(new TelemetryEvent("odii.active_revision.snapshot", Map.of(
                "revisionId", snapshot.revisionId().toString(),
                "storyCount", Integer.toString(snapshot.stories().size())
        )));
    }

    @Override
    public void activeRevisionUnavailable(RuntimeException exception) {
        telemetrySink.record(new TelemetryEvent("odii.active_revision.unavailable", Map.of(
                "reason", exception.getClass().getSimpleName()
        )));
    }
}
