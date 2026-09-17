package com.yrootlab.onmaru.scheduling.retention;

import com.yrootlab.onmaru.observability.TelemetryEvent;
import com.yrootlab.onmaru.observability.TelemetrySink;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupObserver;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupResult;

import java.util.Map;

public final class TelemetryRetentionCleanupObserver implements RetentionCleanupObserver {

    private final TelemetrySink telemetrySink;

    public TelemetryRetentionCleanupObserver(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @Override
    public void record(RetentionCleanupResult result) {
        telemetrySink.record(new TelemetryEvent("operations.retention.cleanup.completed", Map.of(
                "expired_guests", Integer.toString(result.expiredGuests()),
                "expired_sessions", Integer.toString(result.expiredSessions()),
                "expired_runs", Integer.toString(result.expiredRuns()),
                "expired_proposals", Integer.toString(result.expiredProposals()),
                "inactive_revisions", Integer.toString(result.inactiveRevisions()),
                "member_deletion_resources", Integer.toString(result.memberDeletionResources()),
                "ledger_entries", Integer.toString(result.ledgerEntries()))));
    }
}
