package com.yrootlab.onmaru.tourism.catalog;

import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiCatalogSnapshotSource;

import java.time.Clock;
import java.util.concurrent.atomic.AtomicInteger;

public final class TourApiCatalogSyncService {

    private final TourApiCatalogSnapshotSource source;
    private final JdbcTourApiCatalogPublisher publisher;
    private final Clock clock;

    public TourApiCatalogSyncService(
            TourApiCatalogSnapshotSource source,
            JdbcTourApiCatalogPublisher publisher,
            Clock clock
    ) {
        this.source = source;
        this.publisher = publisher;
        this.clock = clock;
    }

    public JdbcTourApiCatalogPublisher.PublishResult syncFullSnapshot() {
        var session = publisher.start(clock.instant());
        var published = new AtomicInteger();
        var quarantined = new AtomicInteger();
        try {
            var snapshot = source.streamFullSnapshot(records -> {
                var page = publisher.stagePage(session, records);
                published.addAndGet(page.publishedCount());
                quarantined.addAndGet(page.quarantinedCount());
            });
            return publisher.complete(session, snapshot.rawCount(), snapshot.providerTotalCount(),
                    published.get(), quarantined.get(), clock.instant());
        } catch (RuntimeException exception) {
            publisher.fail(session, "TOURAPI_SYNC_FAILED", clock.instant());
            throw exception;
        }
    }
}
