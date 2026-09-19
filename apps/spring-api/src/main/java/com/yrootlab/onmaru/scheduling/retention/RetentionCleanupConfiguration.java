package com.yrootlab.onmaru.scheduling.retention;

import com.yrootlab.onmaru.observability.TelemetrySink;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupObserver;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupService;
import com.yrootlab.onmaru.operations.retention.RetentionCleanupStore;
import com.yrootlab.onmaru.persistence.operations.retention.JdbcRetentionCleanupStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
public class RetentionCleanupConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RetentionCleanupStore.class)
    RetentionCleanupStore retentionCleanupStore(DataSource dataSource) {
        return new JdbcRetentionCleanupStore(dataSource);
    }

    @Bean
    @ConditionalOnBean(TelemetrySink.class)
    @ConditionalOnMissingBean(RetentionCleanupObserver.class)
    TelemetryRetentionCleanupObserver telemetryRetentionCleanupObserver(TelemetrySink telemetrySink) {
        return new TelemetryRetentionCleanupObserver(telemetrySink);
    }

    @Bean
    @ConditionalOnBean(RetentionCleanupStore.class)
    @ConditionalOnMissingBean
    RetentionCleanupService retentionCleanupService(
            RetentionCleanupStore store,
            Clock clock,
            RetentionCleanupObserver observer
    ) {
        return new RetentionCleanupService(store, clock, observer);
    }

    @Bean
    @ConditionalOnBean(RetentionCleanupService.class)
    RetentionCleanupJob retentionCleanupJob(RetentionCleanupService service) {
        return new RetentionCleanupJob(service);
    }
}
