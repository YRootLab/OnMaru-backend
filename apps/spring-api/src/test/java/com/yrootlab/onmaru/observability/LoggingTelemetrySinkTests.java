package com.yrootlab.onmaru.observability;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingTelemetrySinkTests {

    @Test
    void formatsSortedSanitizedAttributesInLogMessage() {
        Logger logger = (Logger) LoggerFactory.getLogger(LoggingTelemetrySink.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new LoggingTelemetrySink().record(new TelemetryEvent(
                    "journey.worker.ai_response",
                    Map.of(
                            "run.id", "run-123\nforged",
                            "rag.corpus.revision", "rev-7",
                            "rag.evidence.count", "2")));

            assertThat(appender.list).hasSize(1);
            ILoggingEvent logged = appender.list.getFirst();
            assertThat(logged.getFormattedMessage())
                    .isEqualTo("journey.worker.ai_response rag.corpus.revision=rev-7 rag.evidence.count=2 run.id=run-123 forged");
            assertThat(logged.getKeyValuePairs()).hasSize(3);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
