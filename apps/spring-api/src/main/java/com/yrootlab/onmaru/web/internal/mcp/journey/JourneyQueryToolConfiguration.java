package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;
import com.yrootlab.onmaru.persistence.catalog.journey.JdbcPlaceQueryAdapter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;

@Configuration
class JourneyQueryToolConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlaceQueryPort.class)
    PlaceQueryPort placeQueryPort(ObjectProvider<DataSource> dataSourceProvider) {
        var dataSource = dataSourceProvider.getIfAvailable();
        if (dataSource != null) {
            return new JdbcPlaceQueryAdapter(dataSource);
        }
        return (regionCode, category, limit) -> List.of();
    }

    @Bean
    JourneyQueryToolController journeyQueryToolController(PlaceQueryPort placeQueryPort) {
        return new JourneyQueryToolController(placeQueryPort);
    }
}
