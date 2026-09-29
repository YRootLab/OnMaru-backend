package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapViewportQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
public class MapInfoViewportConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoViewportStore jdbcMapInfoViewportStore(DataSource dataSource) {
        return new JdbcMapViewportQueryRepository(dataSource);
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
