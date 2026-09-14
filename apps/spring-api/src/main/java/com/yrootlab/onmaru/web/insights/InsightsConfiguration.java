package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.query.Coordinates;
import com.yrootlab.onmaru.insights.query.HeatSpot;
import com.yrootlab.onmaru.insights.query.InMemoryInsightsQueryStore;
import com.yrootlab.onmaru.insights.query.InsightsQueryService;
import com.yrootlab.onmaru.insights.query.Observation;
import com.yrootlab.onmaru.insights.query.RegionRef;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.LocalDate;

@Configuration
class InsightsConfiguration {

    @Bean
    InMemoryInsightsQueryStore insightsQueryStore() {
        var store = new InMemoryInsightsQueryStore();
        var jeonju = new RegionRef("kr-45-jeonju", "전북 전주시", "CITY", "kr-45");
        store.save(new Observation(
                "obs-kr-45-jeonju-2026-09-14-visitors",
                jeonju,
                LocalDate.parse("2026-09-14"),
                "VISITOR_COUNT",
                18240L,
                "persons",
                "SIGUNGU",
                "COMPLETE"));
        store.save(new HeatSpot(
                "heat-p-jeonju-hanok-village-2026-09-14",
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                jeonju,
                new Coordinates(35.8151, 127.1530),
                18240L,
                72.4,
                "BUSY",
                1.8,
                "COMPLETE",
                LocalDate.parse("2026-09-14"),
                "CONGESTION_SCORE"));
        return store;
    }

    @Bean
    InsightsQueryService insightsQueryService(InMemoryInsightsQueryStore store, Clock clock) {
        return new InsightsQueryService(store, clock);
    }
}
