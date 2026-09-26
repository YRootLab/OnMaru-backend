package com.yrootlab.onmaru.scheduling.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the DataLab visitor ingestion after the provider's daily data publication window. */
@Component
@Profile("production")
public final class DataLabVisitorSchedulingAdapter {

    private final DataLabVisitorIngestionService ingestionService;

    public DataLabVisitorSchedulingAdapter(DataLabVisitorIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    @Scheduled(cron = "${onmaru.datalab.visitor.sync.cron:0 30 3 * * *}", zone = "Asia/Seoul")
    public void sync() {
        ingestionService.sync();
    }
}
