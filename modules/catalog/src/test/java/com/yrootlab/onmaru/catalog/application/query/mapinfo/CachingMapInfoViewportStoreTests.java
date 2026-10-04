package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CachingMapInfoViewportStoreTests {

    @Test
    void doesNotCachePositiveAggregateCountsWithNoItems() {
        var query = new MapInfoViewportQuery(new MapInfoBounds(126.9, 37.5, 127.1, 37.7),
                9, MapInfoCategory.ALL, null, null, "ko-KR", 60);
        var empty = new InMemoryMapInfoViewportStore().find(query);
        var calls = new AtomicInteger();
        MapInfoViewportStore delegate = ignored -> {
            if (calls.getAndIncrement() == 0) {
                return new MapInfoViewportResponse(empty.schemaVersion(), empty.renderMode(),
                        empty.profileVersion(), empty.snapshot(), 2, List.of(),
                        empty.appliedCategories(), empty.coverage(), empty.servedBbox(), empty.projection());
            }
            return empty;
        };
        var cache = new CachingMapInfoViewportStore(delegate, Duration.ofMinutes(1), 10);

        assertThatThrownBy(() -> cache.find(query)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.find(query).totalCountInViewport()).isZero();
        assertThat(cache.missCount()).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(1);
    }
}
