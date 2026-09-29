package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.CachingMapInfoViewportStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapViewportQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class MapInfoViewportConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoViewportStore jdbcMapInfoViewportStore(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-ttl:30s}") Duration cacheTtl,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.db-statement-timeout:1500ms}") Duration statementTimeout) {
        return new CachingMapInfoViewportStore(new JdbcMapViewportQueryRepository(dataSource, statementTimeout), cacheTtl, 256);
    }

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(MapInfoViewportStore.class)
    MapInfoViewportStore inMemoryMapInfoViewportStore() {
        return new InMemoryMapInfoViewportStore();
    }

    @Bean
    @ConditionalOnBean(MapInfoViewportStore.class)
    MapInfoViewportQueryService mapInfoViewportQueryService(MapInfoViewportStore store) {
        return new MapInfoViewportQueryService(store);
    }
}
