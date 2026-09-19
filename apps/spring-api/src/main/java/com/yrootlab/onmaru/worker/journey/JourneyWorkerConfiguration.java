package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.AiIntegrationProperties;
import com.yrootlab.onmaru.integration.ai.HttpAiProposalClient;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;
import com.yrootlab.onmaru.scheduling.run.JourneyRunSweeper;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.BaselinePlanner;
import com.yrootlab.onmaru.journey.worker.DefaultBaselinePlanner;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.persistence.journey.run.JdbcJourneyRunStore;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyResultStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;

@Configuration
@EnableConfigurationProperties(AiIntegrationProperties.class)
class JourneyWorkerConfiguration {

    @Bean
    JourneyWorkerQueue journeyWorkerQueue() {
        return new InMemoryJourneyWorkerQueue();
    }

    @Bean
    InMemoryJourneyCandidateProvider journeyCandidateProvider() {
        return new InMemoryJourneyCandidateProvider();
    }

    @Bean
    BaselinePlanner baselinePlanner() {
        return new DefaultBaselinePlanner();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyResultStore journeyResultStore(DataSource dataSource) {
        return new JdbcJourneyResultStore(dataSource);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean
    JourneyRunStore journeyRunStore(DataSource dataSource) {
        return new JdbcJourneyRunStore(dataSource);
    }

    @Bean
    @ConditionalOnBean(JourneyRunStore.class)
    @ConditionalOnMissingBean
    JourneyRunCancellationService journeyRunCancellationService(JourneyRunStore runStore) {
        return new JourneyRunCancellationService(runStore);
    }

    @Bean
    @ConditionalOnBean(JourneyRunCancellationService.class)
    JourneyRunSweeper journeyRunSweeper(JourneyRunCancellationService cancellationService, Clock clock) {
        return new JourneyRunSweeper(cancellationService, clock);
    }

    @Bean
    InternalAiRequestHeadersFactory internalAiRequestHeadersFactory(
            SecretProvider secretProvider,
            Clock clock) {
        return new InternalAiRequestHeadersFactory(new InternalAiTokenSigner(
                secretProvider,
                InternalAiTokenProperties.defaults(),
                clock));
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    AiProposalClient aiProposalClient(
            org.springframework.beans.factory.ObjectProvider<ObjectMapper> objectMapperProvider,
            AiIntegrationProperties properties,
            InternalAiRequestHeadersFactory headersFactory,
            JourneyWorkerTelemetry telemetry) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new HttpAiProposalClient(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build(),
                objectMapper,
                properties.baseUrl(),
                headersFactory,
                properties.timeout(),
                telemetry);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyWorkerService journeyWorkerService(
            JourneyRunStore runStore,
            JourneyCandidateProvider candidateProvider,
            AiProposalClient aiProposalClient,
            BaselinePlanner baselinePlanner,
            JourneyResultStore resultStore,
            JourneyWorkerTelemetry telemetry) {
        return new JourneyWorkerService(
                runStore,
                candidateProvider,
                aiProposalClient,
                baselinePlanner,
                resultStore,
                telemetry);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyWorkerRunner journeyWorkerRunner(
            JourneyWorkerQueue queue,
            JourneyWorkerService service) {
        return new JourneyWorkerRunner(queue, service::process);
    }
}
