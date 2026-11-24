package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.AiIntegrationProperties;
import com.yrootlab.onmaru.integration.ai.HttpAiProposalClient;
import com.yrootlab.onmaru.integration.ai.HttpAiPlanExecuteClient;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanVersionsProperties;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanWireCodec;
import com.yrootlab.onmaru.integration.ai.plan.ConfigurableCurrentVersions;
import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import com.yrootlab.onmaru.integration.ai.plan.DefaultPlanAuthorityGate;
import com.yrootlab.onmaru.integration.ai.plan.DefaultSafeQueryFactory;
import com.yrootlab.onmaru.journey.worker.plan.AiPlanExecuteClient;
import com.yrootlab.onmaru.journey.worker.plan.PlanAuthorityGate;
import com.yrootlab.onmaru.journey.worker.plan.PlanExecuteAiProposalClient;
import com.yrootlab.onmaru.journey.worker.plan.ValidatingExecutionResultMapper;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.*;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.cancellation.JourneyRunCancellationService;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcPublicPlaceEligibilityChecker;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationContextReader;
import com.yrootlab.onmaru.scheduling.run.JourneyRunSweeper;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.BaselinePlanner;
import com.yrootlab.onmaru.journey.worker.DefaultBaselinePlanner;
import com.yrootlab.onmaru.journey.worker.FallbackJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyWorkerQueue;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerOutcome;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.persistence.journey.run.JdbcJourneyRunStore;
import com.yrootlab.onmaru.persistence.journey.worker.JdbcJourneyResultStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;

@Configuration
@EnableConfigurationProperties({AiIntegrationProperties.class, AiPlanVersionsProperties.class})
class JourneyWorkerConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(JourneyWorkerConfiguration.class);
    private static final String JOURNEY_AI_OPERATION = "journey.ai";

    /** Production queue: QUEUED rows in discovery_runs. Survives restarts and is shared by all instances. */
    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyWorkerQueue durableJourneyWorkerQueue(DataSource dataSource, Clock clock) {
        return new JdbcJourneyWorkerQueue(dataSource, clock);
    }

    /** Fallback when no database is configured (tests and single-process controlled runs). */
    @Bean
    @ConditionalOnMissingBean(JourneyWorkerQueue.class)
    JourneyWorkerQueue journeyWorkerQueue() {
        return new InMemoryJourneyWorkerQueue();
    }

    @Bean
    InMemoryJourneyCandidateProvider journeyCandidateProvider() {
        return new InMemoryJourneyCandidateProvider();
    }

    /** Production candidates come from the active catalog; in-memory entries (tests/controlled runs) take precedence. */
    @Bean
    @Primary
    @ConditionalOnBean(DataSource.class)
    JourneyCandidateProvider catalogCandidateProvider(InMemoryJourneyCandidateProvider inMemory, DataSource dataSource) {
        return new FallbackJourneyCandidateProvider(inMemory, new JdbcJourneyCandidateProvider(dataSource));
    }

    @Bean
    BaselinePlanner baselinePlanner() {
        return new DefaultBaselinePlanner();
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyResultStore journeyResultStore(DataSource dataSource, Clock clock) {
        return new JdbcJourneyResultStore(dataSource, clock);
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
    JourneyRunSweeper journeyRunSweeper(
            JourneyRunCancellationService cancellationService,
            Clock clock,
            ObjectProvider<AdmissionService> admissionServiceProvider,
            ObjectProvider<AdmissionPolicy> admissionPolicyProvider) {
        return new JourneyRunSweeper(
                cancellationService,
                clock,
                actorKey -> releaseJourneyAiSlot(actorKey, admissionServiceProvider, admissionPolicyProvider));
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
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "false", matchIfMissing = true)
    AiProposalClient aiProposalClient(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            AiIntegrationProperties properties,
            @Qualifier("internalAiRequestHeadersFactory") InternalAiRequestHeadersFactory headersFactory,
            JourneyWorkerTelemetry telemetry,
            @Value("${onmaru.ai.streaming-enabled:${ONMARU_AI_STREAMING_ENABLED:true}}") boolean streamingEnabled,
            @Value("${onmaru.ai.stream-unary-fallback-enabled:${ONMARU_AI_STREAM_UNARY_FALLBACK_ENABLED:true}}") boolean unaryFallbackEnabled) {
        ObjectMapper objectMapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new HttpAiProposalClient(
                aiHttpClient(properties.timeout()),
                objectMapper,
                properties.baseUrl(),
                headersFactory,
                properties.timeout(),
                telemetry,
                streamingEnabled,
                unaryFallbackEnabled);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    AiPlanWireCodec aiPlanWireCodec() { return new AiPlanWireCodec(); }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    PlanAuthorityGate planAuthorityGate(AiPlanWireCodec codec) { return new DefaultPlanAuthorityGate(codec); }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    AiPlanExecuteClient aiPlanExecuteClient(AiIntegrationProperties properties, AiPlanWireCodec codec,
            SecretProvider secrets, Clock clock) {
        return new HttpAiPlanExecuteClient(aiHttpClient(properties.timeout()),
                codec, properties.baseUrl(), secrets, properties.timeout(), clock);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(ExplorationContextReader.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    ExplorationContextReader explorationContextReader(
            DataSource dataSource,
            ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new JdbcExplorationContextReader(
                dataSource,
                objectMapperProvider.getIfAvailable(ObjectMapper::new));
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(CurrentVersions.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    CurrentVersions currentVersions(
            AiPlanVersionsProperties properties,
            JdbcAiSourceRevocationStore sourceRevocations) {
        return new ConfigurableCurrentVersions(properties, sourceRevocations::watermark);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SafeQueryFactory.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    SafeQueryFactory safeQueryFactory(
            ExplorationContextReader contextReader,
            CurrentVersions versions,
            AiPlanWireCodec codec) {
        return new DefaultSafeQueryFactory(contextReader, versions, codec);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(PublicPlaceEligibilityChecker.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    PublicPlaceEligibilityChecker publicPlaceEligibilityChecker(DataSource dataSource) {
        return new JdbcPublicPlaceEligibilityChecker(dataSource);
    }

    /** Re-validates the AI execution result against the approved plan, cited evidence, and live public catalog. */
    @Bean
    @ConditionalOnMissingBean(ExecutionResultMapper.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    ExecutionResultMapper executionResultMapper(ObjectProvider<PublicPlaceEligibilityChecker> eligibilityChecker) {
        return new ValidatingExecutionResultMapper(eligibilityChecker.getIfAvailable());
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnProperty(prefix = "onmaru.ai", name = "plan-execute-enabled", havingValue = "true")
    AiProposalClient planExecuteAiProposalClient(AiPlanExecuteClient client, PlanAuthorityGate gate,
            SafeQueryFactory requests, CurrentVersions versions, ApprovedBindingStore bindings,
            QueryGrantIssuer grants, ProgressCapabilityIssuer progress, ExecutionResultMapper mapper,
            Clock clock) {
        return new PlanExecuteAiProposalClient(client, gate, requests, versions, bindings, grants, progress, mapper, clock);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyWorkerService journeyWorkerService(
            JourneyRunStore runStore,
            JourneyCandidateProvider candidateProvider,
            AiProposalClient aiProposalClient,
            BaselinePlanner baselinePlanner,
            JourneyResultStore resultStore,
            JourneyWorkerTelemetry telemetry,
            ObjectProvider<JourneyRunEventSink> eventSinkProvider,
            Clock clock) {
        return new JourneyWorkerService(
                runStore,
                candidateProvider,
                aiProposalClient,
                baselinePlanner,
                resultStore,
                telemetry,
                eventSinkProvider.getIfAvailable(JourneyRunEventSink::noop),
                clock);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    JourneyWorkerRunner journeyWorkerRunner(
            JourneyWorkerQueue queue,
            JourneyWorkerService service,
            ObjectProvider<AdmissionService> admissionServiceProvider,
            ObjectProvider<AdmissionPolicy> admissionPolicyProvider) {
        return new JourneyWorkerRunner(queue, request -> {
            var outcome = service.process(request);
            if (outcome == JourneyWorkerOutcome.COMPLETED_LLM
                    || outcome == JourneyWorkerOutcome.COMPLETED_BASELINE
                    || outcome == JourneyWorkerOutcome.FAILED) {
                releaseJourneyAiSlot(request.actorKey(), admissionServiceProvider, admissionPolicyProvider);
            }
            return outcome;
        });
    }

    private static HttpClient aiHttpClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
    }

    static void releaseJourneyAiSlot(
            String actorKey,
            ObjectProvider<AdmissionService> admissionServiceProvider,
            ObjectProvider<AdmissionPolicy> admissionPolicyProvider) {
        var admissionService = admissionServiceProvider.getIfAvailable();
        var admissionPolicy = admissionPolicyProvider.getIfAvailable();
        if (admissionService == null || admissionPolicy == null) {
            return;
        }
        var subject = parseAdmissionSubject(actorKey);
        if (subject == null) {
            return;
        }
        try {
            admissionService.releaseActive(new AdmissionRequest(JOURNEY_AI_OPERATION, subject), admissionPolicy);
        } catch (RuntimeException exception) {
            LOGGER.warn("failed to release journey.ai admission slot subjectType={}", subject.type(), exception);
        }
    }

    static AdmissionSubject parseAdmissionSubject(String actorKey) {
        if (actorKey == null || actorKey.isBlank()) {
            return null;
        }
        var normalized = actorKey.startsWith("actor:") ? actorKey.substring("actor:".length()) : actorKey;
        int colon = normalized.indexOf(':');
        if (colon <= 0 || colon == normalized.length() - 1) {
            return null;
        }
        var rawType = normalized.substring(0, colon).trim().toUpperCase(Locale.ROOT);
        var subject = normalized.substring(colon + 1).trim();
        if (subject.isEmpty()) {
            return null;
        }
        return switch (rawType) {
            case "MEMBER" -> new AdmissionSubject(SubjectType.MEMBER, subject);
            case "GUEST" -> new AdmissionSubject(SubjectType.GUEST, subject);
            default -> null;
        };
    }
}
