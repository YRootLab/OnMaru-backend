package com.yrootlab.onmaru.tourism.audio;

import com.yrootlab.onmaru.tourism.audio.client.OdiiClientProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(OdiiClientConfiguration.OdiiSettings.class)
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
}
