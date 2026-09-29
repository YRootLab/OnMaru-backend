package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.CachingMapInfoViewportStore;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapViewportQueryRepository;
import com.yrootlab.onmaru.web.map.MapInfoObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Duration;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
public class MapInfoViewportConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoViewportStore jdbcMapInfoViewportStore(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-ttl:30s}") Duration cacheTtl,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.db-statement-timeout:1500ms}") Duration statementTimeout,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.slow-query-threshold:500ms}") Duration slowQueryThreshold,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-max-entries:256}") int maxEntries,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var registry = meterRegistryProvider.getIfAvailable();
        MapInfoViewportStore delegate = new JdbcMapViewportQueryRepository(dataSource, statementTimeout);
        if (registry != null) delegate = new MapInfoObservation(registry, slowQueryThreshold).observe(delegate, "viewport");
        var cache = new CachingMapInfoViewportStore(delegate, cacheTtl, maxEntries);
        if (registry != null) {
            FunctionCounter.builder("onmaru.map.info.cache.hit", cache, CachingMapInfoViewportStore::hitCount)
                    .tag("endpoint", "viewport").register(registry);
            FunctionCounter.builder("onmaru.map.info.cache.miss", cache, CachingMapInfoViewportStore::missCount)
                    .tag("endpoint", "viewport").register(registry);
            registry.gauge("onmaru.map.info.cache.entries", io.micrometer.core.instrument.Tags.of("endpoint", "viewport"), cache,
                    CachingMapInfoViewportStore::size);
        }
        return cache;
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
