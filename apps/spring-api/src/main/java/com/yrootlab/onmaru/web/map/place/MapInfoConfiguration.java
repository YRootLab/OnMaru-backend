package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.*;
import com.yrootlab.onmaru.persistence.catalog.JdbcMapInfoQueryRepository;
import com.yrootlab.onmaru.web.map.MapInfoRequestExecutor;
import com.yrootlab.onmaru.web.map.MapInfoObservation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration(proxyBeanMethods = false)
class MapInfoConfiguration {
    @Bean(destroyMethod = "shutdown")
    ExecutorService mapInfoRequestExecutorService() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    MapInfoRequestExecutor mapInfoRequestExecutor(
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.api-timeout:2s}") Duration apiTimeout,
            ExecutorService executorService) {
        return new MapInfoRequestExecutor(apiTimeout, executorService);
    }

    @Bean
    @ConditionalOnBean(DataSource.class)
    MapInfoQueryPort jdbcMapInfoQueryPort(
            DataSource dataSource,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-ttl:30s}") Duration cacheTtl,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.db-statement-timeout:1500ms}") Duration statementTimeout,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.slow-query-threshold:500ms}") Duration slowQueryThreshold,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.stale-if-error:30s}") Duration staleIfError,
            @org.springframework.beans.factory.annotation.Value("${onmaru.map.info.cache-max-entries:256}") int maxEntries,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var registry = meterRegistryProvider.getIfAvailable();
        MapInfoQueryPort delegate = new JdbcMapInfoQueryRepository(dataSource, statementTimeout);
        if (registry != null) delegate = new MapInfoObservation(registry, slowQueryThreshold).observe(delegate, "places");
        var cache = new CachingMapInfoQueryPort(delegate, cacheTtl, maxEntries, staleIfError);
        registerCacheMetrics(registry, cache);
        return cache;
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

    private void registerCacheMetrics(MeterRegistry registry, CachingMapInfoQueryPort cache) {
        if (registry == null) return;
        FunctionCounter.builder("onmaru.map.info.cache.hit", cache, CachingMapInfoQueryPort::hitCount)
                .tag("endpoint", "places").register(registry);
        FunctionCounter.builder("onmaru.map.info.cache.miss", cache, CachingMapInfoQueryPort::missCount)
                .tag("endpoint", "places").register(registry);
        registry.gauge("onmaru.map.info.cache.entries", io.micrometer.core.instrument.Tags.of("endpoint", "places"), cache,
                CachingMapInfoQueryPort::size);
    }
}
