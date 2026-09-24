package com.yrootlab.onmaru.persistence.saved;

import com.yrootlab.onmaru.audio.query.InMemoryOdiiStoryPopularityCounter;
import com.yrootlab.onmaru.audio.query.OdiiStoryPopularityPort;
import com.yrootlab.onmaru.journey.saved.odii.SavedOdiiStoryStore;
import com.yrootlab.onmaru.journey.saved.place.SavedPlaceStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

/**
 * 운영(DataSource 존재)에서 찜·인기 신호를 Neon DB로 영속화한다.
 * 로컬/테스트는 인메모리 구현이 그대로 사용된다.
 */
@Configuration
public class SavedPersistenceConfiguration {

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SavedPlaceStore.class)
    SavedPlaceStore jdbcSavedPlaceStore(DataSource dataSource) {
        return new JdbcSavedPlaceStore(dataSource);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SavedOdiiStoryStore.class)
    SavedOdiiStoryStore jdbcSavedOdiiStoryStore(DataSource dataSource) {
        return new JdbcSavedOdiiStoryStore(dataSource);
    }

    @Bean
    @Profile("!production")
    @ConditionalOnMissingBean(OdiiStoryPopularityPort.class)
    OdiiStoryPopularityPort inMemoryOdiiStoryPopularityPort() {
        return new InMemoryOdiiStoryPopularityCounter();
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(OdiiStoryPopularityPort.class)
    OdiiStoryPopularityPort jdbcOdiiStoryPopularityPort(DataSource dataSource) {
        return new JdbcOdiiStoryPopularityStore(dataSource);
    }
}
