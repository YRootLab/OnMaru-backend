package com.yrootlab.onmaru.scheduling.catalog.detail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.List;

/** 하루 한 번 실행되어 일일 상한까지 상세를 수집한다. 본문과 키는 로그에 남기지 않고 건수만 남긴다. */
public final class DetailCollectionJob {

    private static final Logger LOGGER = LoggerFactory.getLogger(DetailCollectionJob.class);

    private final DetailCollectionPolicy policy;
    private final DetailCollectionStore store;
    private final DetailFetcher fetcher;
    private final Clock clock;

    public DetailCollectionJob(
            DetailCollectionPolicy policy,
            DetailCollectionStore store,
            DetailFetcher fetcher,
            Clock clock) {
        this.policy = policy;
        this.store = store;
        this.fetcher = fetcher;
        this.clock = clock;
    }

    public DetailCollectionReport runOnce() {
        try (AutoCloseable lock = store.tryLock()) {
            if (lock == null) {
                return logged(DetailCollectionReport.skipped("LOCK_HELD"));
            }
            int remaining = policy.remaining(store.attemptsToday());
            if (remaining == 0) {
                return logged(DetailCollectionReport.skipped("DAILY_CAP_REACHED"));
            }
            List<DetailCollectionTarget> targets = store.nextTargets(remaining);
            int ok = 0;
            int failed = 0;
            String stoppedBy = null;
            for (DetailCollectionTarget target : targets) {
                DetailFetchResult result = fetcher.fetch(target.contentId());
                store.save(target, result, clock.instant());
                if (result.status() == DetailFetchResult.Status.OK) {
                    ok++;
                } else {
                    failed++;
                }
                if (result.stopRun()) {
                    stoppedBy = result.errorCode();
                    break;
                }
            }
            return logged(new DetailCollectionReport(ok, failed, stoppedBy, null));
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("detail collection lock release failed", exception);
        }
    }

    private DetailCollectionReport logged(DetailCollectionReport report) {
        if (report.ran()) {
            LOGGER.info("TourAPI detail collection finished: ok={}, failed={}, stoppedBy={}",
                    report.okCount(), report.failedCount(), report.stoppedBy());
        } else {
            LOGGER.info("TourAPI detail collection skipped: reason={}", report.skippedReason());
        }
        return report;
    }
}
