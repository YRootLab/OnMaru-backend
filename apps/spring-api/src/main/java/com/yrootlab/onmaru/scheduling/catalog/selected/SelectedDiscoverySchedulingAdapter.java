package com.yrootlab.onmaru.scheduling.catalog.selected;

import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

/** Separate, off-by-default weekly clock. Startup catches only the latest due slot. */
@Component
@ConditionalOnProperty(prefix = "onmaru.discovery", name = "enabled", havingValue = "true")
@ConditionalOnExpression("'${onmaru.discovery.operator.action:}' == ''")
public final class SelectedDiscoverySchedulingAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SelectedDiscoverySchedulingAdapter.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final SelectedDiscoverySync service;
    private final ThreadPoolTaskScheduler scheduler;
    private final Clock clock;

    public SelectedDiscoverySchedulingAdapter(SelectedDiscoverySync service,
                                               ThreadPoolTaskScheduler selectedDiscoveryTaskScheduler, Clock clock) {
        this.service = service; this.scheduler = selectedDiscoveryTaskScheduler; this.clock = clock;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchLatestDueOnStartup() { scheduler.execute(this::runLatestDue); }

    @Scheduled(cron = "${onmaru.discovery.cron:0 0 3 * * MON}", zone = "Asia/Seoul", scheduler = "selectedDiscoveryTaskScheduler")
    public void scheduledRun() { runLatestDue(); }

    void runLatestDue() {
        Instant due = latestDue(clock.instant());
        UUID runId = UUID.nameUUIDFromBytes(("selected-discovery:" + due).getBytes(StandardCharsets.UTF_8));
        try {
            var result = service.run(runId, due);
            LOGGER.info("Selected discovery sync finished: runId={}, dueAt={}, result={}", runId, due, result);
        } catch (RuntimeException exception) {
            LOGGER.error("Selected discovery sync failed: runId={}, dueAt={}, reason={}", runId, due, exception.getClass().getSimpleName());
        }
    }

    static Instant latestDue(Instant now) {
        ZonedDateTime local = now.atZone(SEOUL);
        ZonedDateTime monday = local.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .with(LocalTime.of(3, 0)).withSecond(0).withNano(0);
        if (monday.isAfter(local)) monday = monday.minusWeeks(1);
        return monday.toInstant();
    }
}
