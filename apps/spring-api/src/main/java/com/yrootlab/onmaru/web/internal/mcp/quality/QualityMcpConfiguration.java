package com.yrootlab.onmaru.web.internal.mcp.quality;

import com.yrootlab.onmaru.catalog.application.query.quality.PublicSourceStateQueryPort;
import com.yrootlab.onmaru.journey.observation.RunObservationQueryPort;
import com.yrootlab.onmaru.persistence.catalog.quality.JdbcPublicSourceStateAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Optional;

@Configuration
public class QualityMcpConfiguration {

    @Bean
    @ConditionalOnMissingBean(PublicSourceStateQueryPort.class)
    public PublicSourceStateQueryPort publicSourceStateQueryPort(ObjectProvider<DataSource> dataSourceProvider) {
        DataSource dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            return new JdbcPublicSourceStateAdapter(dataSource);
        }
        return (sourceId, sourceRevision) -> Optional.empty();
    }

    @Bean
    @ConditionalOnMissingBean(QualityMcpController.class)
    public QualityMcpController qualityMcpController(
            ObjectProvider<RunObservationQueryPort> runObservationQueryPortProvider,
            PublicSourceStateQueryPort publicSourceStateQueryPort) {
        RunObservationQueryPort runObservationQueryPort = runObservationQueryPortProvider.getIfAvailable(
                () -> runId -> Optional.empty());
        return new QualityMcpController(runObservationQueryPort, publicSourceStateQueryPort);
    }
}
