package com.yrootlab.onmaru.scheduling.catalog.detail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/** 매일 04:30 KST에 실행한다. 목록 갱신(03:00) 이후 시간대라 두 작업이 겹치지 않는다. */
@Component
@Profile("!staging")
@ConditionalOnExpression("'${ONMARU_SECRET_TOURAPI_DETAIL_COLLECTION_KEY_CURRENT:}' != ''")
public class DetailCollectionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DetailCollectionScheduler.class);

    private final DetailCollectionJob job;
    private final boolean runOnStartup;

    public DetailCollectionScheduler(
            DetailCollectionJob job,
            @Value("${onmaru.tourapi.detail-collection.run-on-startup:false}") boolean runOnStartup) {
        this.job = job;
        this.runOnStartup = runOnStartup;
    }

    /** 수동 실행 옵션이 켜진 배포에서만 기동 직후 한 번 실행한다. 상한과 잠금은 scheduledRun과 같다. */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        if (!runOnStartup) {
            return;
        }
        LOGGER.info("TourAPI detail collection startup run requested");
        CompletableFuture.runAsync(this::scheduledRun);
    }

    @Scheduled(cron = "${onmaru.tourapi.detail-collection.cron:0 30 4 * * *}", zone = "Asia/Seoul")
    public void scheduledRun() {
        try {
            job.runOnce();
        } catch (RuntimeException exception) {
            LOGGER.error("TourAPI detail collection failed: reason={}", exception.getClass().getSimpleName());
        }
    }
}
