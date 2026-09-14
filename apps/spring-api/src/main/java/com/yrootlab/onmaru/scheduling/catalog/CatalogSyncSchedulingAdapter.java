package com.yrootlab.onmaru.scheduling.catalog;

import com.yrootlab.onmaru.catalog.application.sync.CatalogSyncScheduler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public final class CatalogSyncSchedulingAdapter {

    private final ObjectProvider<CatalogSyncScheduler> schedulerProvider;

    CatalogSyncSchedulingAdapter(ObjectProvider<CatalogSyncScheduler> schedulerProvider) {
        this.schedulerProvider = schedulerProvider;
    }

    @Scheduled(fixedDelayString = "${onmaru.catalog.sync.scan-delay-ms:300000}")
    public void scanDueSchedules() {
        var scheduler = schedulerProvider.getIfAvailable();
        if (scheduler != null) {
            scheduler.scanDueSchedules();
        }
    }
}
