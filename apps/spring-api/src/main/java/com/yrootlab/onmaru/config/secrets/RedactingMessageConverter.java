package com.yrootlab.onmaru.config.secrets;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.List;

public final class RedactingMessageConverter extends ClassicConverter {

    private static volatile SecretRedactor redactor = new SecretRedactor(List.of());

    public static void install(SecretRedactor secretRedactor) {
        redactor = secretRedactor;
    }

    @Override
    public String convert(ILoggingEvent event) {
        return redactor.redact(event.getFormattedMessage());
    }
}
