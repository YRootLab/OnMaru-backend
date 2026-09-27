package com.yrootlab.onmaru.scheduling.catalog;

import com.yrootlab.onmaru.tourism.catalog.TourApiCatalogSyncService;
import com.yrootlab.onmaru.tourism.catalog.TourApiClientConfiguration.TourApiSyncSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class TourApiCatalogSyncSchedulingAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TourApiCatalogSyncSchedulingAdapter.class);
    private final ObjectProvider<TourApiCatalogSyncService> serviceProvider;
    private final TourApiSyncSettings settings;

    public TourApiCatalogSyncSchedulingAdapter(
            ObjectProvider<TourApiCatalogSyncService> serviceProvider,
            TourApiSyncSettings settings
    ) {
        this.serviceProvider = serviceProvider;
        this.settings = settings;
    }

    @Scheduled(cron = "${onmaru.tourapi.sync.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void scheduledSync() {
        run("scheduled-cron");
    }

    public synchronized void run(String trigger) {
        var service = serviceProvider.getIfAvailable();
        if (service == null) return;
        if (!service.isSyncDue(settings.minimumInterval())) {
            LOGGER.info("TourAPI catalog sync skipped: trigger={}, minimumInterval={}",
                    trigger, settings.minimumInterval());
            return;
        }
        try {
            var result = service.syncFullSnapshot();
            LOGGER.info("TourAPI catalog sync completed: trigger={}, raw={}, published={}, quarantined={}, revision={}",
                    trigger, result.rawCount(), result.publishedCount(), result.quarantinedCount(), result.revisionId());
        } catch (RuntimeException exception) {
            LOGGER.error("TourAPI catalog sync failed: trigger={}, reason={}", trigger, exception.getMessage(), exception);
        }
    }
}
