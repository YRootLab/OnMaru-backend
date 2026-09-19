package com.yrootlab.onmaru.tourism.catalog;

import com.yrootlab.onmaru.tourism.catalog.client.TourApiClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(TourApiClientConfiguration.TourApiSettings.class)
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
}
