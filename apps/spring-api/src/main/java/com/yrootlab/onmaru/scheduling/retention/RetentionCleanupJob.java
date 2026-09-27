package com.yrootlab.onmaru.scheduling.retention;

import com.yrootlab.onmaru.operations.retention.RetentionCleanupPolicy;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupResult;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public final class RetentionCleanupJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetentionCleanupJob.class);

    private final RetentionCleanupService service;

    public RetentionCleanupJob(RetentionCleanupService service) {
        this.service = service;
    }

    @Scheduled(cron = "${onmaru.retention.cleanup.cron:0 0 4 * * *}", zone = "Asia/Seoul")
    public RetentionCleanupResult runOnce() {
        try {
            return service.runOnce(RetentionCleanupPolicy.defaults());
        } catch (RuntimeException exception) {
            LOGGER.atError()
                    .setCause(exception)
                    .log("operations.retention.cleanup.failed");
            throw exception;
        }
    }
}
