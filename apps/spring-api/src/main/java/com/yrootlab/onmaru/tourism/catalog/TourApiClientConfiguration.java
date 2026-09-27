package com.yrootlab.onmaru.tourism.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.persistence.catalog.JdbcTourApiCatalogPublisher;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiClientProperties;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiCatalogSnapshotSource;
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
        TourApiClientConfiguration.TourApiSettings.class,
        TourApiClientConfiguration.TourApiSyncSettings.class
})
public class TourApiClientConfiguration {

    @Bean
    TourApiClientProperties tourApiClientProperties(TourApiSettings settings) {
        return new TourApiClientProperties(
                settings.connectTimeout(),
                settings.attemptTimeout(),
                settings.pageTimeout(),
                settings.retryCount()
        );
    }

    @Bean
    @Profile("production")
    TourApiHttpClient tourApiHttpClient(ObjectMapper objectMapper, TourApiClientProperties properties) {
        return new TourApiHttpClient(objectMapper, properties);
    }

    @Bean
    @Profile("production")
    TourApiCatalogSnapshotSource tourApiCatalogSnapshotSource(
            TourApiHttpClient client,
            SecretProvider secrets,
            TourApiSyncSettings settings
    ) {
        return new TourApiCatalogSnapshotSource(client, new TourApiUriBuilder(
                settings.baseUri(), secrets.get("tourapi.service-key").current(), settings.mobileApp()),
                settings.pageSize());
    }

    @Bean
    @Profile("production")
    TourApiCatalogSyncService tourApiCatalogSyncService(
            TourApiCatalogSnapshotSource source,
            JdbcTourApiCatalogPublisher publisher,
            Clock clock
    ) {
        return new TourApiCatalogSyncService(source, publisher, clock);
    }

    @ConfigurationProperties(prefix = "onmaru.tourapi.client")
    public record TourApiSettings(
            Duration connectTimeout,
            Duration attemptTimeout,
            Duration pageTimeout,
            Integer retryCount
    ) {
        public TourApiSettings {
            TourApiClientProperties defaults = TourApiClientProperties.defaults();
            connectTimeout = connectTimeout == null ? defaults.connectTimeout() : connectTimeout;
            attemptTimeout = attemptTimeout == null ? defaults.attemptTimeout() : attemptTimeout;
            pageTimeout = pageTimeout == null ? defaults.pageTimeout() : pageTimeout;
            retryCount = retryCount == null ? defaults.retryCount() : retryCount;
        }
    }

    @ConfigurationProperties(prefix = "onmaru.tourapi.sync")
    public record TourApiSyncSettings(
            URI baseUri,
            String mobileApp,
            Integer pageSize,
            Duration minimumInterval
    ) {
        public TourApiSyncSettings {
            baseUri = baseUri == null
                    ? URI.create("https://apis.data.go.kr/B551011/KorService2") : baseUri;
            mobileApp = mobileApp == null || mobileApp.isBlank() ? "OnMaru" : mobileApp;
            pageSize = pageSize == null ? 1000 : pageSize;
            minimumInterval = minimumInterval == null ? Duration.ofHours(72) : minimumInterval;
            if (pageSize < 1 || pageSize > 1000) {
                throw new IllegalArgumentException("pageSize must be between 1 and 1000");
            }
            if (minimumInterval.isNegative() || minimumInterval.isZero()) {
                throw new IllegalArgumentException("minimumInterval must be positive");
            }
        }
    }
}
