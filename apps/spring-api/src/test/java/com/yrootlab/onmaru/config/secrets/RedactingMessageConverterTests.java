package com.yrootlab.onmaru.config.secrets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RedactingMessageConverterTests {

    @Test
    void redactsSecretsFromLogbackFormattedMessages() {
        RedactingMessageConverter.install(new SecretRedactor(List.of(
                new SecretBundle("tourapi.service-key", "tourapi-current-secret", Optional.of("tourapi-previous-secret")))));
        RedactingMessageConverter converter = new RedactingMessageConverter();

        LoggingEvent event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage("current={} previous={}");
        event.setArgumentArray(new Object[]{"tourapi-current-secret", "tourapi-previous-secret"});

        assertThat(converter.convert(event)).isEqualTo("current=<REDACTED> previous=<REDACTED>");
    }
}
