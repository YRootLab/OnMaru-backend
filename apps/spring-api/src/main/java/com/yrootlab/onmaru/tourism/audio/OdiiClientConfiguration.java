package com.yrootlab.onmaru.tourism.audio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.OdiiSyncObserver;
import com.yrootlab.onmaru.audio.sync.OdiiSourceMapper;
import com.yrootlab.onmaru.observability.TelemetrySink;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.tourism.audio.client.OdiiClientProperties;
import com.yrootlab.onmaru.tourism.audio.client.OdiiHttpClient;
import com.yrootlab.onmaru.tourism.audio.client.OdiiUriBuilder;
import com.yrootlab.onmaru.tourism.audio.mapping.OdiiSourceItemMapper;
import com.yrootlab.onmaru.tourism.audio.sync.OdiiStorySearchPageSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties({
        OdiiClientConfiguration.OdiiSettings.class,
        OdiiClientConfiguration.OdiiSyncSettings.class
})
public class OdiiClientConfiguration {

    @Bean
    OdiiClientProperties odiiClientProperties(OdiiSettings settings) {
        return new OdiiClientProperties(
                settings.connectTimeout(),
                settings.attemptTimeout(),
                settings.pageTimeout(),
                settings.retryCount()
        );
    }

    @Bean
    @Profile("production")
    OdiiHttpClient odiiHttpClient(ObjectMapper objectMapper, OdiiClientProperties properties) {
        return new OdiiHttpClient(objectMapper, properties);
    }

    @Bean
    @Profile("production")
    OdiiStorySearchPageSource odiiStorySearchPageSource(
            OdiiHttpClient client,
            SecretProvider secretProvider,
            OdiiSyncSettings settings
    ) {
        var uriBuilder = new OdiiUriBuilder(
                settings.baseUri(),
                secretProvider.get("odii.service-key").current(),
                settings.mobileApp());
        return new OdiiStorySearchPageSource(
                client,
                uriBuilder,
                new OdiiSourceItemMapper(),
                settings.keyword(),
                settings.pageSize());
    }

    @Bean
    @Profile("production")
    OdiiRevisionSyncService odiiRevisionSyncService(
            AudioRevisionStore store,
            OdiiStorySearchPageSource source,
            Clock clock,
            OdiiSyncObserver observer
    ) {
        return new OdiiRevisionSyncService(store, source, new OdiiSourceMapper(), clock, observer);
    }

    @Bean
    @Profile("production")
    OdiiSyncObserver odiiSyncObserver(TelemetrySink telemetrySink) {
        return new TelemetryOdiiSyncObserver(telemetrySink);
    }

    @ConfigurationProperties(prefix = "onmaru.odii.client")
    public record OdiiSettings(
            Duration connectTimeout,
            Duration attemptTimeout,
            Duration pageTimeout,
            Integer retryCount
    ) {
        public OdiiSettings {
            OdiiClientProperties defaults = OdiiClientProperties.defaults();
            connectTimeout = connectTimeout == null ? defaults.connectTimeout() : connectTimeout;
            attemptTimeout = attemptTimeout == null ? defaults.attemptTimeout() : attemptTimeout;
            pageTimeout = pageTimeout == null ? defaults.pageTimeout() : pageTimeout;
            retryCount = retryCount == null ? defaults.retryCount() : retryCount;
        }
    }

    @ConfigurationProperties(prefix = "onmaru.odii.sync")
    public record OdiiSyncSettings(
            URI baseUri,
            String mobileApp,
            String keyword,
            Integer pageSize
    ) {
        public OdiiSyncSettings {
            baseUri = baseUri == null ? URI.create("https://apis.data.go.kr/B551011/Odii") : baseUri;
            mobileApp = mobileApp == null || mobileApp.isBlank() ? "OnMaru" : mobileApp;
            keyword = keyword == null || keyword.isBlank() ? "한옥" : keyword;
            pageSize = pageSize == null ? 100 : pageSize;
            if (pageSize < 1 || pageSize > 1000) {
                throw new IllegalArgumentException("pageSize must be between 1 and 1000");
            }
        }
    }
}
