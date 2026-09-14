package com.yrootlab.onmaru.tourism.catalog;

import com.yrootlab.onmaru.tourism.catalog.client.TourApiClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "onmaru.tourapi.client.connect-timeout=2s",
        "onmaru.tourapi.client.attempt-timeout=6s",
        "onmaru.tourapi.client.page-timeout=13s",
        "onmaru.tourapi.client.retry-count=0",
        "onmaru.secrets.source=fake"
})
class TourApiClientConfigurationTests {

    @Autowired
    private TourApiClientProperties properties;

    @Test
    void bindsTourApiClientPropertiesFromSpringConfiguration() {
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.attemptTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.pageTimeout()).isEqualTo(Duration.ofSeconds(13));
        assertThat(properties.retryCount()).isZero();
    }

    @Test
    void rejectsInvalidTourApiClientConfiguration() {
        TourApiClientConfiguration configuration = new TourApiClientConfiguration();
        TourApiClientConfiguration.TourApiSettings settings = new TourApiClientConfiguration.TourApiSettings(
                Duration.ZERO,
                Duration.ofSeconds(5),
                Duration.ofSeconds(12),
                2
        );

        assertThatThrownBy(() -> configuration.tourApiClientProperties(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectTimeout");
    }

    @Test
    void rejectsNegativeRetryCountConfiguration() {
        TourApiClientConfiguration configuration = new TourApiClientConfiguration();
        TourApiClientConfiguration.TourApiSettings settings = new TourApiClientConfiguration.TourApiSettings(
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(12),
                -1
        );

        assertThatThrownBy(() -> configuration.tourApiClientProperties(settings))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryCount");
    }
}
