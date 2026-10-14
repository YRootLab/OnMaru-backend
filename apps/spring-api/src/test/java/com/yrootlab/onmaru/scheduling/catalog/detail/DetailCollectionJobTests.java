package com.yrootlab.onmaru.scheduling.catalog.detail;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DetailCollectionJobTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-14T19:30:00Z"), ZoneOffset.UTC);

    @Test
    void remainingBudgetLimitsHowManyDetailsAreRequested() {
        var store = new FakeStore(750, true, targets(100));
        var fetched = new ArrayList<String>();
        var job = new DetailCollectionJob(new DetailCollectionPolicy(800), store,
                target -> {
                    fetched.add(target.contentId());
                    return DetailFetchResult.ok(null);
                }, CLOCK);

        var report = job.runOnce();

        assertThat(store.requestedLimit).isEqualTo(50);
        assertThat(fetched).hasSize(50);
        assertThat(report.okCount()).isEqualTo(50);
        assertThat(report.ran()).isTrue();
    }

    @Test
    void dailyCapReachedSkipsWithoutFetching() {
        var store = new FakeStore(800, true, targets(10));
        var fetched = new ArrayList<String>();
        var job = new DetailCollectionJob(new DetailCollectionPolicy(800), store,
                target -> {
                    fetched.add(target.contentId());
                    return DetailFetchResult.ok(null);
                }, CLOCK);

        var report = job.runOnce();

        assertThat(fetched).isEmpty();
        assertThat(report.skippedReason()).isEqualTo("DAILY_CAP_REACHED");
    }

    @Test
    void rateLimitSavesThatItemAndStopsTheRun() {
        var store = new FakeStore(0, true, targets(5));
        var fetched = new ArrayList<String>();
        var job = new DetailCollectionJob(new DetailCollectionPolicy(800), store,
                target -> {
                    fetched.add(target.contentId());
                    return fetched.size() == 2
                            ? DetailFetchResult.failed("RATE_LIMITED", true)
                            : DetailFetchResult.ok(null);
                }, CLOCK);

        var report = job.runOnce();

        assertThat(fetched).hasSize(2);
        assertThat(store.saved).hasSize(2);
        assertThat(report.stoppedBy()).isEqualTo("RATE_LIMITED");
        assertThat(report.okCount()).isEqualTo(1);
        assertThat(report.failedCount()).isEqualTo(1);
    }

    @Test
    void heldLockSkipsWithoutFetching() {
        var store = new FakeStore(0, false, targets(5));
        var fetched = new ArrayList<String>();
        var job = new DetailCollectionJob(new DetailCollectionPolicy(800), store,
                target -> {
                    fetched.add(target.contentId());
                    return DetailFetchResult.ok(null);
                }, CLOCK);

        var report = job.runOnce();

        assertThat(fetched).isEmpty();
        assertThat(report.skippedReason()).isEqualTo("LOCK_HELD");
    }

    @Test
    void policyRejectsCapAboveSharedLimit() {
        assertThatThrownBy(() -> new DetailCollectionPolicy(801))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DetailCollectionPolicy(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<DetailCollectionTarget> targets(int count) {
        var list = new ArrayList<DetailCollectionTarget>();
        for (int i = 1; i <= count; i++) {
            list.add(new DetailCollectionTarget("c" + i, "12", "HANOK"));
        }
        return list;
    }

    private static final class FakeStore implements DetailCollectionStore {
        private final int attempts;
        private final boolean lockAvailable;
        private final List<DetailCollectionTarget> pool;
        private final List<DetailCollectionTarget> saved = new ArrayList<>();
        private int requestedLimit = -1;

        private FakeStore(int attempts, boolean lockAvailable, List<DetailCollectionTarget> pool) {
            this.attempts = attempts;
            this.lockAvailable = lockAvailable;
            this.pool = pool;
        }

        @Override
        public int attemptsToday() {
            return attempts;
        }

        @Override
        public List<DetailCollectionTarget> nextTargets(int limit) {
            requestedLimit = limit;
            return pool.subList(0, Math.min(limit, pool.size()));
        }

        @Override
        public void save(DetailCollectionTarget target, DetailFetchResult result, Instant attemptedAt) {
            saved.add(target);
        }

        @Override
        public AutoCloseable tryLock() {
            return lockAvailable ? () -> { } : null;
        }
    }
}
