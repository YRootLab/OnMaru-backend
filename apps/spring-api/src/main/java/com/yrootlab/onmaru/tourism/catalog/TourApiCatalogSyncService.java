package com.yrootlab.onmaru.tourism.catalog;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiCatalogSnapshotSource;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public final class TourApiCatalogSyncService {

    private final TourApiCatalogSnapshotSource source;
    private final JdbcTourApiCatalogPublisher publisher;
    private final Clock clock;
    private final boolean selectedSource;

    public TourApiCatalogSyncService(
            TourApiCatalogSnapshotSource source,
            JdbcTourApiCatalogPublisher publisher,
            Clock clock
    ) {
        this(source, publisher, clock, false);
    }

    public TourApiCatalogSyncService(
            TourApiCatalogSnapshotSource source,
            JdbcTourApiCatalogPublisher publisher,
            Clock clock,
            boolean selectedSource
    ) {
        this.source = source;
        this.publisher = publisher;
        this.clock = clock;
        this.selectedSource = selectedSource;
    }

    public JdbcTourApiCatalogPublisher.PublishResult syncFullSnapshot() {
        var session = publisher.start(clock.instant());
        var published = new AtomicInteger();
        var quarantined = new AtomicInteger();
        var skipped = new AtomicInteger();
        try {
            Consumer<List<SourceRecord>> stage = records -> {
                var page = publisher.stagePage(session, records);
                published.addAndGet(page.publishedCount());
                quarantined.addAndGet(page.quarantinedCount());
                skipped.addAndGet(page.skippedCount());
            };
            var snapshot = selectedSource ? source.streamSelectedSnapshot(stage) : source.streamFullSnapshot(stage);
            return publisher.complete(session, snapshot.rawCount(), snapshot.providerTotalCount(),
                    published.get(), quarantined.get(), skipped.get(), clock.instant());
        } catch (RuntimeException exception) {
            publisher.fail(session, "TOURAPI_SYNC_FAILED", clock.instant());
            throw exception;
        }
    }

    public boolean isSyncDue(Duration minimumInterval) {
        return publisher.isSyncDue(clock.instant(), minimumInterval);
    }
}
