package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Clock;

public final class CatalogSyncScheduler {

    private final SyncScheduleRepository repository;
    private final Clock clock;

    public CatalogSyncScheduler(SyncScheduleRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public int scanDueSchedules() {
        var now = clock.instant();
        var registered = 0;
        for (var schedule : repository.findDueSchedules(now)) {
            if (repository.registerRunAndAdvanceSchedule(schedule.dataset(), schedule.nextDueAt(), now)) {
                registered++;
            }
        }
        return registered;
    }
}
