package com.yrootlab.onmaru.catalog.application.sync;

import java.time.Instant;
import java.util.List;

public interface SyncScheduleRepository {

    List<SyncSchedule> findDueSchedules(Instant now);

    boolean registerRunAndAdvanceSchedule(String dataset, Instant scheduledFor, Instant now);
}
