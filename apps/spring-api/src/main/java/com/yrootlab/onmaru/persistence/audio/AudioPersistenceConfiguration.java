package com.yrootlab.onmaru.persistence.audio;

import com.yrootlab.onmaru.audio.placelink.AudioPlaceLinkStore;
import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.observability.TelemetrySink;
import com.yrootlab.onmaru.tourism.audio.JdbcAudioRevisionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;

@Configuration
public class AudioPersistenceConfiguration {

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AudioRevisionStore.class)
    AudioRevisionStore jdbcAudioRevisionStore(
            DataSource dataSource,
            @Value("${onmaru.audio.dataset:odii-audio}") String dataset
    ) {
        var store = new JdbcAudioRevisionStore(dataSource);
        store.initializeDataset(dataset, java.time.Instant.now());
        return store;
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(AudioPlaceLinkStore.class)
    AudioPlaceLinkStore jdbcAudioPlaceLinkStore(
            DataSource dataSource,
            ObjectProvider<TelemetrySink> telemetrySink
    ) {
        return new JdbcAudioPlaceLinkStore(dataSource, telemetrySink.getIfAvailable(() -> ignored -> {
        }));
    }
}
