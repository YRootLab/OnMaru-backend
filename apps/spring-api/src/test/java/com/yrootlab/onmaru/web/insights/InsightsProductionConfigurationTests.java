package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorRevisionWriter;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorSource;
import com.yrootlab.onmaru.insights.query.InMemoryInsightsQueryStore;
import com.yrootlab.onmaru.insights.query.InsightsQueryService;
import com.yrootlab.onmaru.catalog.region.DataLabRegionMappingRegistry;
import com.yrootlab.onmaru.persistence.catalog.JdbcDataLabRegionMappingRegistry;
import com.yrootlab.onmaru.insights.ingestion.DataLabVisitorFetchResult;
import com.yrootlab.onmaru.persistence.insights.JdbcDataLabVisitorSnapshotPublisher;
import com.yrootlab.onmaru.tourism.insights.DataLabVisitorClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

class InsightsProductionConfigurationTests {

    @Test
    void productionWiresJdbcPublisherCatalogLookupAndDataLabSourceWhenDatabaseIsAvailable() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withPropertyValues("onmaru.datalab.visitor.service-key=integration-test-key")
                .withBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource("jdbc:invalid"))
                .withBean(com.fasterxml.jackson.databind.ObjectMapper.class, com.fasterxml.jackson.databind.ObjectMapper::new)
                .withBean(java.time.Clock.class, java.time.Clock::systemUTC)
                .withUserConfiguration(InsightsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataLabVisitorClient.class)).isNotNull();
                    assertThat(context.getBean(DataLabRegionMappingRegistry.class))
                            .isInstanceOf(JdbcDataLabRegionMappingRegistry.class);
                    assertThat(context.getBean(DataLabVisitorRevisionWriter.class))
                            .isInstanceOf(JdbcDataLabVisitorSnapshotPublisher.class);
                    assertThat(context.getBean(DataLabVisitorSource.class))
                            .isInstanceOf(DataLabVisitorSourceAdapter.class);
                    assertThat(context).hasSingleBean(InsightsQueryService.class);
                });
    }

    @Test
    void productionUsesSuppliedDataLabBoundariesWithoutCreatingInsightFixtures() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withBean(DataLabVisitorSource.class, () -> () ->
                        new DataLabVisitorFetchResult(java.util.List.of(), java.util.List.of(), false))
                .withBean(DataLabVisitorRevisionWriter.class, () -> observations -> { })
                .withUserConfiguration(InsightsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DataLabVisitorSource.class);
                    assertThat(context).hasSingleBean(DataLabVisitorRevisionWriter.class);
                    assertThat(context).doesNotHaveBean(InMemoryInsightsQueryStore.class);
                });
    }

    @Test
    void productionDoesNotInstallAnInMemoryDataLabFallbackWhenSourceIsMissing() {
        new ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("production"))
                .withUserConfiguration(InsightsConfiguration.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasMessageContaining("DataLabVisitorSource");
                });
    }
}
