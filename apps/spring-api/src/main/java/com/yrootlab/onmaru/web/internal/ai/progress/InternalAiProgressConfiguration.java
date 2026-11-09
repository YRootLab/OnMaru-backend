package com.yrootlab.onmaru.web.internal.ai.progress;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.progress.AiRunProgressStore;
import com.yrootlab.onmaru.integration.ai.progress.JdbcAiRunProgressStore;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ApprovedBindingStore;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ProgressCapabilityIssuer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration
class InternalAiProgressConfiguration {

    @Bean
    InternalAiProgressVerifier internalAiProgressVerifier(ObjectProvider<SecretProvider> secretProvider, Clock clock) {
        return new InternalAiProgressVerifier(
                secretProvider.getIfAvailable(() -> name -> null),
                HmacProgressCapabilityIssuer.SECRET_NAME,
                clock);
    }

    /** Without a database no binding can exist, so every callback is rejected rather than accepted unguarded. */
    @Bean
    AiRunProgressStore aiRunProgressStore(ObjectProvider<DataSource> dataSource, Clock clock) {
        var source = dataSource.getIfAvailable();
        return source != null ? new JdbcAiRunProgressStore(source, clock) : new UnavailableProgressStore();
    }

    @Bean
    ApprovedBindingStore approvedBindingStore(AiRunProgressStore store) {
        return (ApprovedBindingStore) store;
    }

    @Bean
    ProgressCapabilityIssuer progressCapabilityIssuer(ObjectProvider<SecretProvider> secrets, Clock clock) {
        SecretProvider provider = secrets.getIfAvailable(() -> name -> {
            throw new IllegalStateException("service secret provider is not configured");
        });
        return new HmacProgressCapabilityIssuer(provider, clock);
    }

    @Bean
    InternalAiProgressController internalAiProgressController(
            InternalAiProgressVerifier verifier,
            @Qualifier("aiRunProgressStore") AiRunProgressStore progress,
            ObjectProvider<JourneyRunEventSink> eventSink) {
        return new InternalAiProgressController(verifier, progress, eventSink.getIfAvailable(JourneyRunEventSink::noop));
    }

    private static final class UnavailableProgressStore implements AiRunProgressStore, ApprovedBindingStore {
        @Override
        public void bind(com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding binding,
                         java.time.Instant deadline) {
            throw new IllegalStateException("approved plan binding store is not configured");
        }

        @Override
        public java.util.Optional<String> boundPlanHash(java.util.UUID runId) {
            return java.util.Optional.empty();
        }

        @Override
        public Result record(java.util.UUID runId, String planHash, int ordinal,
                             com.yrootlab.onmaru.journey.run.JourneyRunStage stage) {
            return new Result(Decision.NOT_BOUND, null);
        }
    }
}
