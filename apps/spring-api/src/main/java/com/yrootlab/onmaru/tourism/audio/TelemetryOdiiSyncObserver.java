package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.audio.sync.OdiiSyncObserver;
import com.yrootlab.onmaru.audio.sync.OdiiSyncResult;
import com.yrootlab.onmaru.observability.TelemetryEvent;
import com.yrootlab.onmaru.observability.TelemetrySink;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class TelemetryOdiiSyncObserver implements OdiiSyncObserver {

    private final TelemetrySink telemetrySink;

    TelemetryOdiiSyncObserver(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @Override
    public void started(String dataset, UUID expectedRevisionId) {
        telemetrySink.record(new TelemetryEvent("odii.sync.started", Map.of(
                "dataset", dataset,
                "expectedRevisionId", expectedRevisionId.toString()
        )));
    }

    @Override
    public void failed(String dataset, UUID revisionId, String failureCode) {
        telemetrySink.record(new TelemetryEvent("odii.sync.failed", Map.of(
                "dataset", dataset,
                "revisionId", revisionId.toString(),
                "failureCode", failureCode
        )));
    }

    @Override
    public void completed(String dataset, OdiiSyncResult result) {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("dataset", dataset);
        attributes.put("revisionId", result.stagedRevisionId().toString());
        attributes.put("status", result.status().name());
        attributes.put("itemCount", Long.toString(result.itemCount()));
        attributes.put("tombstoneCount", Long.toString(result.tombstoneCount()));
        telemetrySink.record(new TelemetryEvent("odii.sync.completed", attributes));
    }
}
