package com.yrootlab.onmaru.observability.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.observation.RunObservationQueryPort;
import com.yrootlab.onmaru.journey.observation.RunObservationStore;
import com.yrootlab.onmaru.persistence.journey.observation.JdbcRunObservationStore;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class RunObservationConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(RunObservationStore.class)
    public RunObservationStore runObservationStore(DataSource dataSource, ObjectProvider<ObjectMapper> objectMapperProvider) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new JdbcRunObservationStore(dataSource, objectMapper);
    }

    @Bean
    @ConditionalOnBean(RunObservationStore.class)
    @ConditionalOnMissingBean(RunObservationQueryPort.class)
    public RunObservationQueryPort runObservationQueryPort(RunObservationStore runObservationStore) {
        return runObservationStore;
    }

    @Bean
    public AiRunObservationMetrics aiRunObservationMetrics(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable(io.micrometer.core.instrument.simple.SimpleMeterRegistry::new);
        return new AiRunObservationMetrics(meterRegistry);
    }
}
