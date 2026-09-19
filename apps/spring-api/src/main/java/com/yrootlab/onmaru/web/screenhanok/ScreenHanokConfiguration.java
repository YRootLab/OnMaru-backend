package com.yrootlab.onmaru.web.screenhanok;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.catalog.screenhanok.InMemoryScreenHanokPlacementStore;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokIngestionService;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokQueryService;
import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokResearchPort;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.integration.ai.AiIntegrationProperties;
import com.yrootlab.onmaru.integration.ai.screenhanok.HttpScreenHanokResearchClient;
import com.yrootlab.onmaru.integration.ai.security.InternalAiRequestHeadersFactory;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenProperties;
import com.yrootlab.onmaru.integration.ai.security.InternalAiTokenSigner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/**
 * Wires the screen-hanok feature (ADR-0010). The placement store is in-memory for now, matching
 * this repository's current MonthlyHanokEdition precedent; JDBC persistence is a known follow-up.
 */
@Configuration
@EnableConfigurationProperties(AiIntegrationProperties.class)
class ScreenHanokConfiguration {

    private static final InternalAiTokenProperties TOKEN_PROPERTIES = new InternalAiTokenProperties(
            "onmaru-spring",
            "spring-api",
            "onmaru-ai",
            "screen-hanok.research:write",
            Duration.ofSeconds(60),
            "internal-ai.service-token");

    @Bean
    InMemoryScreenHanokPlacementStore screenHanokPlacementStore() {
        return new InMemoryScreenHanokPlacementStore();
    }

    @Bean
    ScreenHanokQueryService screenHanokQueryService(
            InMemoryScreenHanokPlacementStore placementStore,
            InMemoryHanokListStore hanokListStore,
            HanokSavedStateLookup savedStateLookup) {
        return new ScreenHanokQueryService(placementStore, hanokListStore, savedStateLookup);
    }

    @Bean
    InternalAiRequestHeadersFactory screenHanokRequestHeadersFactory(SecretProvider secretProvider, Clock clock) {
        return new InternalAiRequestHeadersFactory(new InternalAiTokenSigner(secretProvider, TOKEN_PROPERTIES, clock));
    }

    @Bean
    ScreenHanokResearchPort screenHanokResearchPort(
            ObjectProvider<ObjectMapper> objectMapperProvider,
            AiIntegrationProperties aiIntegrationProperties,
            InternalAiRequestHeadersFactory screenHanokRequestHeadersFactory) {
        ObjectMapper mapper = objectMapperProvider.getIfAvailable(ObjectMapper::new);
        return new HttpScreenHanokResearchClient(
                HttpClient.newBuilder().connectTimeout(aiIntegrationProperties.timeout()).build(),
                mapper,
                aiIntegrationProperties.baseUrl(),
                screenHanokRequestHeadersFactory,
                aiIntegrationProperties.timeout());
    }

    @Bean
    ScreenHanokIngestionService screenHanokIngestionService(
            InMemoryHanokListStore hanokListStore,
            ScreenHanokResearchPort screenHanokResearchPort,
            InMemoryScreenHanokPlacementStore placementStore,
            Clock clock) {
        return new ScreenHanokIngestionService(hanokListStore, screenHanokResearchPort, placementStore, clock);
    }
}
