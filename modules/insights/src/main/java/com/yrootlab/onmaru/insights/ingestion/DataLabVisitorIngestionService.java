package com.yrootlab.onmaru.insights.ingestion;

import java.util.Objects;
import java.util.EnumMap;

/** Coordinates fetch-before-replace so a failed fetch never changes the active revision. */
public final class DataLabVisitorIngestionService {

    private final DataLabVisitorSource source;
    private final DataLabVisitorRevisionWriter revisionWriter;
    private final DataLabCollectionObserver observer;
    private final DataLabCollectionGuard guard;

    public DataLabVisitorIngestionService(
            DataLabVisitorSource source,
            DataLabVisitorRevisionWriter revisionWriter) {
        this(source, revisionWriter, DataLabCollectionObserver.NOOP, new InMemoryDataLabCollectionGuard());
    }

    public DataLabVisitorIngestionService(
            DataLabVisitorSource source,
            DataLabVisitorRevisionWriter revisionWriter,
            DataLabCollectionObserver observer) {
        this(source, revisionWriter, observer, new InMemoryDataLabCollectionGuard());
    }

    public DataLabVisitorIngestionService(
            DataLabVisitorSource source,
            DataLabVisitorRevisionWriter revisionWriter,
            DataLabCollectionObserver observer,
            DataLabCollectionGuard guard) {
        this.source = Objects.requireNonNull(source);
        this.revisionWriter = Objects.requireNonNull(revisionWriter);
        this.observer = Objects.requireNonNull(observer);
        this.guard = Objects.requireNonNull(guard);
    }

    public DataLabVisitorSyncResult sync() {
        DataLabCollectionGuard.Lease lease = guard.tryAcquire()
                .orElseThrow(DataLabCollectionAlreadyRunningException::new);
        try (lease) {
            return syncWhileLocked();
        }
    }

    private DataLabVisitorSyncResult syncWhileLocked() {
        DataLabVisitorFetchResult result = source.fetchDailyVisitorObservations();
        var reasons = new EnumMap<DataLabCollectionReason, Long>(DataLabCollectionReason.class);
        int skipped = 0;
        int quarantined = 0;
        for (DataLabCollectionExclusion exclusion : result.exclusions()) {
            DataLabCollectionOutcome outcome = outcome(exclusion.reason());
            observer.record(outcome, exclusion.reason());
            reasons.merge(exclusion.reason(), 1L, Long::sum);
            if (outcome == DataLabCollectionOutcome.SKIPPED) {
                skipped++;
            } else {
                quarantined++;
            }
        }
        if (result.publishable()) {
            revisionWriter.replaceActive(result.observations());
            observer.record(DataLabCollectionOutcome.PUBLISHED, null);
            return new DataLabVisitorSyncResult(
                    true, result.observations().size(), skipped, quarantined, reasons);
        }
        return new DataLabVisitorSyncResult(false, 0, skipped, quarantined, reasons);
    }

    private DataLabCollectionOutcome outcome(DataLabCollectionReason reason) {
        return switch (reason) {
            case PENDING_MAPPING, REJECTED_MAPPING, NO_ACTIVE_MAPPING -> DataLabCollectionOutcome.SKIPPED;
            default -> DataLabCollectionOutcome.QUARANTINED;
        };
    }
}
