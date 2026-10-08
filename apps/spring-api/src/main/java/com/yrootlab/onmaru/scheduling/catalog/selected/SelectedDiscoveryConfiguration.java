package com.yrootlab.onmaru.scheduling.catalog.selected;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoveryApprovalService;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.persistence.catalog.selected.JdbcSelectedDiscoveryStore;
import com.yrootlab.onmaru.tourism.catalog.TourApiClientConfiguration.TourApiSyncSettings;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.selected.SelectedDiscoveryTourApiSources;
import com.yrootlab.onmaru.tourism.catalog.selected.SelectedTourApiPageSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

@Configuration
@Profile("production")
public class SelectedDiscoveryConfiguration {
    @Bean
    JdbcSelectedDiscoveryStore jdbcSelectedDiscoveryStore(DataSource dataSource, ObjectMapper json) {
        return new JdbcSelectedDiscoveryStore(dataSource, json);
    }

    @Bean
    TourApiUriBuilder selectedDiscoveryUris(SecretProvider secrets, TourApiSyncSettings settings) {
        return new TourApiUriBuilder(settings.baseUri(), secrets.get("tourapi.service-key").current(), settings.mobileApp());
    }

    @Bean
    SelectedDiscoveryTourApiSources selectedDiscoveryTourApiSources(TourApiHttpClient client, TourApiUriBuilder selectedDiscoveryUris) {
        return new SelectedDiscoveryTourApiSources(client::get, selectedDiscoveryUris);
    }

    @Bean
    SelectedDiscoverySync selectedDiscoverySync(TourApiHttpClient client, TourApiUriBuilder selectedDiscoveryUris,
                                                SelectedDiscoveryTourApiSources sources, JdbcSelectedDiscoveryStore store,
                                                TourApiSyncSettings settings) {
        return new SelectedDiscoverySync(new SelectedTourApiPageSource(client::get, selectedDiscoveryUris),
                sources, sources, store, settings.pageSize());
    }

    @Bean
    SelectedDiscoveryApprovalService selectedDiscoveryApprovalService(JdbcSelectedDiscoveryStore store,
                                                                       SelectedDiscoveryTourApiSources sources) {
        return new SelectedDiscoveryApprovalService(store, sources, sources);
    }

    @Bean(name = "selectedDiscoveryTaskScheduler")
    @ConditionalOnProperty(prefix = "onmaru.discovery", name = "enabled", havingValue = "true")
    @ConditionalOnExpression("'${onmaru.discovery.operator.action:}' == ''")
    ThreadPoolTaskScheduler selectedDiscoveryTaskScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("selected-discovery-");
        return scheduler;
    }
}
