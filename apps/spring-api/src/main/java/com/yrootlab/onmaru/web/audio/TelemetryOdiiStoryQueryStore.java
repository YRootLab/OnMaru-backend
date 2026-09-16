package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiActiveSnapshot;
import com.yrootlab.onmaru.audio.query.OdiiStoryQueryStore;
import com.yrootlab.onmaru.observability.TelemetryEvent;
import com.yrootlab.onmaru.observability.TelemetrySink;

import java.util.Map;

final class TelemetryOdiiStoryQueryStore implements OdiiStoryQueryStore {

    private final OdiiStoryQueryStore delegate;
    private final TelemetrySink telemetrySink;

    TelemetryOdiiStoryQueryStore(OdiiStoryQueryStore delegate, TelemetrySink telemetrySink) {
        this.delegate = delegate;
        this.telemetrySink = telemetrySink;
    }

    @Override
    public OdiiActiveSnapshot activeSnapshot() {
        try {
            OdiiActiveSnapshot snapshot = delegate.activeSnapshot();
            telemetrySink.record(new TelemetryEvent("odii.active_revision.snapshot", Map.of(
                    "revisionId", snapshot.revisionId().toString(),
                    "storyCount", Integer.toString(snapshot.stories().size())
            )));
            return snapshot;
        } catch (RuntimeException exception) {
            telemetrySink.record(new TelemetryEvent("odii.active_revision.unavailable", Map.of(
                    "reason", exception.getClass().getSimpleName()
            )));
            throw exception;
        }
    }
}
