package com.yrootlab.onmaru.operations.retention;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetentionCleanupServiceTests {

    @Test
    void passesFakeClockAndRecordsObserverEvent() {
        var now = Instant.parse("2026-09-17T00:00:00Z");
        var store = new CapturingStore(new RetentionCleanupResult(3, 2, 1, 4, 1, 5, 7));
        var observer = new CapturingObserver();
        var service = new RetentionCleanupService(store, Clock.fixed(now, ZoneOffset.UTC), observer);
        var policy = RetentionCleanupPolicy.defaults();

        var result = service.runOnce(policy);

        assertThat(store.observedPolicy).isEqualTo(policy);
        assertThat(store.observedNow).isEqualTo(now);
        assertThat(result.ledgerEntries()).isEqualTo(7);
        assertThat(observer.events).containsExactly(result);
    }

    @Test
    void rejectsMissingPolicyBeforeStoreMutation() {
        var store = new CapturingStore(new RetentionCleanupResult(0, 0, 0, 0, 0, 0, 0));
        var service = new RetentionCleanupService(
                store,
                Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC),
                RetentionCleanupObserver.NOOP);

        assertThatThrownBy(() -> service.runOnce(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("policy");
        assertThat(store.observedNow).isNull();
    }

    private static final class CapturingStore implements RetentionCleanupStore {

        private final RetentionCleanupResult result;
        private RetentionCleanupPolicy observedPolicy;
        private Instant observedNow;

        private CapturingStore(RetentionCleanupResult result) {
            this.result = result;
        }

        @Override
        public RetentionCleanupResult cleanup(RetentionCleanupPolicy policy, Instant now) {
            observedPolicy = policy;
            observedNow = now;
            return result;
        }
    }

    private static final class CapturingObserver implements RetentionCleanupObserver {

        private final List<RetentionCleanupResult> events = new ArrayList<>();

        @Override
        public void record(RetentionCleanupResult result) {
            events.add(result);
        }
    }
}
