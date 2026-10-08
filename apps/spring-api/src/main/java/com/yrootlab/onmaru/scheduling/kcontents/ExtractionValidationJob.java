package com.yrootlab.onmaru.scheduling.kcontents;

import com.yrootlab.onmaru.persistence.kcontents.JdbcExtractionValidationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Small bounded batches preserve the independent research queue and legacy publication path. */
@Component
@ConditionalOnProperty(name="onmaru.kcontents.research.enabled",havingValue="true")
public final class ExtractionValidationJob {
    private final JdbcExtractionValidationService validation;

    public ExtractionValidationJob(JdbcExtractionValidationService validation) { this.validation = validation; }

    @Scheduled(fixedDelayString="${onmaru.kcontents.validation.delay-ms:30000}")
    public void run() { validation.processAvailable(50); }
}
