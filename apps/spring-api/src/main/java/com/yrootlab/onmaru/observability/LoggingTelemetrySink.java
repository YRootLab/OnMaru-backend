package com.yrootlab.onmaru.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

@Component
class LoggingTelemetrySink implements TelemetrySink {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingTelemetrySink.class);

    @Override
    public void record(TelemetryEvent event) {
        var builder = LOGGER.atInfo();
        event.attributes().forEach(builder::addKeyValue);
        builder.log("{}", formatMessage(event));
    }

    static String formatMessage(TelemetryEvent event) {
        if (event.attributes().isEmpty()) {
            return sanitize(event.name());
        }
        String attributes = event.attributes().entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(entry -> sanitize(entry.getKey()) + "=" + sanitize(entry.getValue()))
                .collect(Collectors.joining(" "));
        return sanitize(event.name()) + " " + attributes;
    }

    private static String sanitize(String value) {
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
