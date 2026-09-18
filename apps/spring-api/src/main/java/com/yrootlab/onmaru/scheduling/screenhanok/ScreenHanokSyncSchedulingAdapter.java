package com.yrootlab.onmaru.scheduling.screenhanok;

import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokIngestionService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Daily K-content research batch (ADR-0010): 03:00 KST by default. */
@Component
public final class ScreenHanokSyncSchedulingAdapter {

    private final ObjectProvider<ScreenHanokIngestionService> ingestionServiceProvider;

    ScreenHanokSyncSchedulingAdapter(ObjectProvider<ScreenHanokIngestionService> ingestionServiceProvider) {
        this.ingestionServiceProvider = ingestionServiceProvider;
    }

    @Scheduled(cron = "${onmaru.screen-hanok.sync.cron:0 0 3 * * *}", zone = "Asia/Seoul")
    public void sync() {
        var ingestionService = ingestionServiceProvider.getIfAvailable();
        if (ingestionService != null) {
            ingestionService.sync();
        }
    }
}
