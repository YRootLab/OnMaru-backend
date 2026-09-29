package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapInfoQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

@Configuration(proxyBeanMethods = false)
class MapInfoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoQueryPort jdbcMapInfoQueryPort(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-ttl:30s}") Duration cacheTtl,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.db-statement-timeout:1500ms}") Duration statementTimeout) {
        return new CachingMapInfoQueryPort(new JdbcMapInfoQueryRepository(dataSource, statementTimeout), cacheTtl, 256);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(MapInfoQueryPort.class)
    MapInfoQueryPort inMemoryMapInfoQueryPort() {
        return query -> new MapInfoQueryResult(
                new MapInfoSnapshot("dev-map-info", Instant.EPOCH, "PUBLISHED"),
                new MapInfoProjectionPublication(
                        "map_place_read_projection", "dev-map-info", Instant.EPOCH, "dev", 0, "map-category-v1"),
                0, java.util.List.of(), null, false);
    }

    @Bean
    MapInfoSavedStatePort mapInfoSavedStatePort() {
        return MapInfoSavedStatePort.noOp();
    }

    @Bean
    @ConditionalOnBean(MapInfoQueryPort.class)
    MapInfoQueryService mapInfoQueryService(MapInfoQueryPort port, MapInfoSavedStatePort savedStatePort) {
        return new MapInfoQueryService(port, savedStatePort);
    }

    @Bean
    @Profile("production")
    MapInfoLegacyQueryAdapter mapInfoLegacyQueryAdapter(MapInfoQueryService queryService) {
        return new MapInfoLegacyQueryAdapter(queryService);
    }
}
