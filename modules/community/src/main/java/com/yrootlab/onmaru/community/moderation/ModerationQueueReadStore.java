package com.yrootlab.onmaru.community.moderation;

import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.catalog.application.pagination.AdminPage;

import java.time.Instant;

/** Bounded read model for operational/admin moderation queues. */
public interface ModerationQueueReadStore {
    AdminPage<ModerationQueueItem> page(int limit, AdminCursor cursor, Instant now);

    long oldestQueueAgeSeconds(Instant now);
}
