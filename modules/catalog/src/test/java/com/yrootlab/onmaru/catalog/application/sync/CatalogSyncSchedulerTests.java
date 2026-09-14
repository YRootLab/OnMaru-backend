package com.yrootlab.onmaru.catalog.application.sync;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogSyncSchedulerTests {

    @Test
    void registersOneCatchUpRunAfterServerSleptPastKstThreeAm() {
        var repository = new InMemoryScheduleRepository(List.of(
                new SyncSchedule("kto-korean-tour", ZoneId.of("Asia/Seoul"), LocalTime.of(3, 0),
                        Instant.parse("2026-09-14T18:00:00Z"), true)
        ));
        var scheduler = new CatalogSyncScheduler(
                repository,
                Clock.fixed(Instant.parse("2026-09-15T01:00:00Z"), ZoneOffset.UTC)
        );

        scheduler.scanDueSchedules();
        scheduler.scanDueSchedules();

        assertThat(repository.registeredRuns)
                .containsExactly(new ScheduledSyncRun("kto-korean-tour", Instant.parse("2026-09-14T18:00:00Z"), 1));
        assertThat(repository.schedule("kto-korean-tour").orElseThrow().nextDueAt())
                .isEqualTo(Instant.parse("2026-09-15T18:00:00Z"));
    }

    static final class InMemoryScheduleRepository implements SyncScheduleRepository {

        private final List<SyncSchedule> schedules = new ArrayList<>();
        private final List<ScheduledSyncRun> registeredRuns = new ArrayList<>();

        InMemoryScheduleRepository(List<SyncSchedule> schedules) {
            this.schedules.addAll(schedules);
        }

        @Override
        public List<SyncSchedule> findDueSchedules(Instant now) {
            return schedules.stream()
                    .filter(SyncSchedule::enabled)
                    .filter(schedule -> !schedule.nextDueAt().isAfter(now))
                    .toList();
        }

        @Override
        public boolean registerRunAndAdvanceSchedule(String dataset, Instant scheduledFor, Instant now) {
            if (registeredRuns.stream().anyMatch(run -> run.dataset().equals(dataset)
                    && run.scheduledFor().equals(scheduledFor))) {
                return false;
            }
            registeredRuns.add(new ScheduledSyncRun(dataset, scheduledFor, 1));
            schedules.replaceAll(schedule -> schedule.dataset().equals(dataset)
                    ? schedule.advanceAfter(now)
                    : schedule);
            return true;
        }

        Optional<SyncSchedule> schedule(String dataset) {
            return schedules.stream().filter(schedule -> schedule.dataset().equals(dataset)).findFirst();
        }
    }
}
