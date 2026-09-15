package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.tourism.audio.client.OdiiClientProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "onmaru.odii.client.connect-timeout=2s",
        "onmaru.odii.client.attempt-timeout=6s",
        "onmaru.odii.client.page-timeout=13s",
        "onmaru.odii.client.retry-count=0",
        "onmaru.secrets.source=fake"
})
class OdiiClientConfigurationTests {

    @Autowired
    private OdiiClientProperties properties;

    @Test
    void bindsOdiiClientPropertiesFromSpringConfiguration() {
        assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.attemptTimeout()).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.pageTimeout()).isEqualTo(Duration.ofSeconds(13));
        assertThat(properties.retryCount()).isZero();
    }
}
