package com.yrootlab.onmaru.web.internal.ai.progress;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.events.JourneyRunEventSink;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
class InternalAiProgressConfiguration {

    @Bean
    InternalAiProgressVerifier internalAiProgressVerifier(
            ObjectProvider<SecretProvider> secretProvider,
            Clock clock) {
        return new InternalAiProgressVerifier(
                secretProvider.getIfAvailable(() -> name -> null),
                "internal-ai-service-token",
                clock);
    }

    @Bean
    InternalAiProgressController internalAiProgressController(
            InternalAiProgressVerifier verifier,
            ObjectProvider<JourneyRunEventSink> eventSink) {
        return new InternalAiProgressController(
                verifier,
                eventSink.getIfAvailable(JourneyRunEventSink::noop));
    }
}
