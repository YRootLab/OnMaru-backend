package com.yrootlab.onmaru.scheduling.catalog.detail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 04:30 KST에 실행한다. 목록 갱신(03:00) 이후 시간대라 두 작업이 겹치지 않는다. */
@Component
@Profile("!staging")
@ConditionalOnExpression("'${ONMARU_SECRET_TOURAPI_DETAIL_COLLECTION_KEY_CURRENT:}' != ''")
public class DetailCollectionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DetailCollectionScheduler.class);

    private final DetailCollectionJob job;

    public DetailCollectionScheduler(DetailCollectionJob job) {
        this.job = job;
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
