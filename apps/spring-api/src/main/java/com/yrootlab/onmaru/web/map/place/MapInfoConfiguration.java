package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapInfoQueryRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
class MapInfoConfiguration {
    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoQueryPort jdbcMapInfoQueryPort(DataSource dataSource) {
        return new JdbcMapInfoQueryRepository(dataSource);
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
}
