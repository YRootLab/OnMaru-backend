package com.yrootlab.onmaru.web.insights;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.region.CatalogRegionSourceCodeLookup;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorIngestionService;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorRevisionWriter;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorSource;
import com.yrootlab.onmaru.insights.query.Coordinates;
import com.yrootlab.onmaru.insights.query.HeatSpot;
import com.yrootlab.onmaru.insights.query.InMemoryInsightsQueryStore;
import com.yrootlab.onmaru.insights.query.InsightsQueryService;
import com.yrootlab.onmaru.insights.query.Observation;
import com.yrootlab.onmaru.insights.query.RegionRef;
import com.yrootlab.onmaru.persistence.catalog.JdbcCatalogRegionSourceCodeLookup;
import com.yrootlab.onmaru.persistence.insights.JdbcDataLabVisitorSnapshotPublisher;
import com.yrootlab.onmaru.persistence.insights.JdbcInsightsQueryStore;
import com.yrootlab.onmaru.tourism.insights.DataLabClientProperties;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;

@Configuration
@EnableConfigurationProperties(InsightsConfiguration.DataLabVisitorSettings.class)
class InsightsConfiguration {

    @Bean
    @Profile("!production")
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
    @Profile("!production")
    InsightsQueryService insightsQueryService(InMemoryInsightsQueryStore store, Clock clock) {
        return new InsightsQueryService(store, clock);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    InsightsQueryService jdbcInsightsQueryService(DataSource dataSource, Clock clock) {
        return new InsightsQueryService(new JdbcInsightsQueryStore(dataSource), clock);
    }

    @Bean
    @Profile("production")
    DataLabVisitorIngestionService dataLabVisitorIngestionService(
            DataLabVisitorSource source,
            DataLabVisitorRevisionWriter revisionWriter) {
        return new DataLabVisitorIngestionService(source, revisionWriter);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DataLabVisitorClient.class)
    DataLabVisitorClient dataLabVisitorClient(ObjectMapper objectMapper, DataLabVisitorSettings settings) {
        return new DataLabVisitorClient(objectMapper, settings.clientProperties());
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(CatalogRegionSourceCodeLookup.class)
    CatalogRegionSourceCodeLookup catalogRegionSourceCodeLookup(DataSource dataSource) {
        return new JdbcCatalogRegionSourceCodeLookup(dataSource);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DataLabVisitorRevisionWriter.class)
    DataLabVisitorRevisionWriter dataLabVisitorRevisionWriter(DataSource dataSource) {
        return new JdbcDataLabVisitorSnapshotPublisher(dataSource);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(DataLabVisitorSource.class)
    DataLabVisitorSource dataLabVisitorSource(
            DataLabVisitorClient client,
            CatalogRegionSourceCodeLookup regionSourceCodes,
            Clock clock,
            DataLabVisitorSettings settings) {
        return new DataLabVisitorSourceAdapter(client, regionSourceCodes, clock, settings.pageSize());
    }

    @ConfigurationProperties(prefix = "onmaru.datalab.visitor")
    record DataLabVisitorSettings(
            URI baseUri,
            String serviceKey,
            String mobileApp,
            Duration connectTimeout,
            Duration attemptTimeout,
            Duration pageTimeout,
            Integer retryCount,
            Integer pageSize) {

        DataLabVisitorSettings {
            baseUri = baseUri == null ? URI.create("https://apis.data.go.kr/B551011/DataLabService") : baseUri;
            mobileApp = mobileApp == null || mobileApp.isBlank() ? "OnMaru" : mobileApp;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(1) : connectTimeout;
            attemptTimeout = attemptTimeout == null ? Duration.ofSeconds(5) : attemptTimeout;
            pageTimeout = pageTimeout == null ? Duration.ofSeconds(12) : pageTimeout;
            retryCount = retryCount == null ? 2 : retryCount;
            pageSize = pageSize == null ? 100 : pageSize;
            if (pageSize < 1 || pageSize > 1_000) {
                throw new IllegalArgumentException("DataLab pageSize must be between 1 and 1000");
            }
        }

        DataLabClientProperties clientProperties() {
            return new DataLabClientProperties(
                    baseUri, serviceKey, mobileApp, connectTimeout, attemptTimeout, pageTimeout, retryCount);
        }
    }
}
