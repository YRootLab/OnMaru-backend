package com.yrootlab.onmaru.scheduling.admin;

import com.yrootlab.onmaru.admin.auth.AdminJtiRevocationStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

public final class AdminJtiRevocationCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminJtiRevocationCleanupJob.class);

    private final AdminJtiRevocationStore store;
    private final Clock clock;
    private final int batchSize;
    private final int maxBatches;

    public AdminJtiRevocationCleanupJob(
            AdminJtiRevocationStore store, Clock clock, int batchSize, int maxBatches) {
        if (batchSize < 1 || maxBatches < 1) {
            throw new IllegalArgumentException("cleanup bounds must be positive");
        }
        this.store = store;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    @Scheduled(cron = "${onmaru.admin.jwt-revocation.cleanup-cron:0 10 * * * *}", zone = "Asia/Seoul")
    public int runOnce() {
        int total = 0;
        try {
            for (int batch = 0; batch < maxBatches; batch++) {
                int deleted = store.deleteExpired(clock.instant(), batchSize);
                total += deleted;
                if (deleted < batchSize) {
                    break;
                }
            }
            LOGGER.atInfo().addKeyValue("deleted", total).log("admin.jwt.revocation.cleanup.completed");
            return total;
        } catch (RuntimeException exception) {
            LOGGER.atError().setCause(exception).log("admin.jwt.revocation.cleanup.failed");
            throw exception;
        }
    }
}
