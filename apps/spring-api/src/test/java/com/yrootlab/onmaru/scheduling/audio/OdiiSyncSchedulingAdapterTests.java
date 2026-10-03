package com.yrootlab.onmaru.scheduling.audio;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.ObjectProvider;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OdiiSyncSchedulingAdapterTests {

    @Test
    void beanCreationFailureProducesOneSafeTerminalEventInsteadOfEscaping() {
        ObjectProvider<OdiiRevisionSyncService> service = mock(ObjectProvider.class);
        when(service.getIfAvailable()).thenThrow(new IllegalStateException("secret-provider-key"));
        var scheduler = new OdiiSyncSchedulingAdapter(service, mock(ObjectProvider.class),
                mock(ObjectProvider.class), "odii-audio", List.of("ko"));
        var logger = (Logger) LoggerFactory.getLogger(OdiiSyncSchedulingAdapter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatCode(() -> scheduler.runSync("application-ready")).doesNotThrowAnyException();
            assertThat(appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.startsWith("ODII_SYNC_TERMINAL"))).singleElement()
                    .satisfies(message -> assertThat(message).contains("status=FAILED", "phase=DEPENDENCY_CHECK",
                            "reason=COMPONENT_CREATION_FAILED").doesNotContain("secret-provider-key"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void runsTheFullCollectionEveryThreeDaysAtThreeAmKoreaTime() throws Exception {
        Scheduled scheduled = OdiiSyncSchedulingAdapter.class
                .getMethod("scheduledSync")
                .getAnnotation(Scheduled.class);

        assertThat(scheduled.cron()).isEqualTo("${onmaru.odii.sync.cron:0 0 3 */3 * *}");
        assertThat(scheduled.zone()).isEqualTo("Asia/Seoul");
    }
}
