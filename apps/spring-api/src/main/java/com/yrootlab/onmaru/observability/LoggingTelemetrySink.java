package com.yrootlab.onmaru.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class LoggingTelemetrySink implements TelemetrySink {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingTelemetrySink.class);

    @Override
    public void record(TelemetryEvent event) {
        var builder = LOGGER.atInfo();
        event.attributes().forEach(builder::addKeyValue);
        builder.log(event.name());
    }
}
