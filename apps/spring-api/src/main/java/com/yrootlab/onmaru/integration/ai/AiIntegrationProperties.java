package com.yrootlab.onmaru.integration.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties("onmaru.ai")
public record AiIntegrationProperties(
        URI baseUrl,
        Duration timeout) {

    public AiIntegrationProperties {
        if (baseUrl == null) {
            baseUrl = URI.create("http://localhost:8000");
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(12);
        }
    }
}
