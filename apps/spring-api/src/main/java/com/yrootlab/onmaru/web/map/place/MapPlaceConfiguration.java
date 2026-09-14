package com.yrootlab.onmaru.web.map.place;

import com.yrootlab.onmaru.catalog.application.query.spatial.InMemoryMapPlaceStore;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoordinates;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapCoverageStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapDataAvailability;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceQueryService;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapPlaceStatus;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapRegionRef;
import com.yrootlab.onmaru.catalog.application.query.spatial.MapSavedStateLookup;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class MapPlaceConfiguration {

    @Bean
    InMemoryMapPlaceStore mapPlaceStore() {
        var store = new InMemoryMapPlaceStore();
        store.add(place(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "한옥",
                "kr-45-jeonju",
                "전북 전주시",
                35.8151,
                127.1530,
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("odii-story-jeonju-hanok-01")));
        store.add(place(
                "p-jeonju-gyodong-tea",
                "교동 찻집",
                "카페",
                "kr-45-jeonju",
                "전북 전주시",
                35.8159,
                127.1540,
                "한옥 골목 사이의 조용한 찻집입니다.",
                List.of()));
        return store;
    }

    @Bean
    MapSavedStateLookup mapSavedStateLookup(InMemorySavedPlaceStore savedPlaceStore) {
        return (memberId, placeId) -> memberId
                .map(id -> savedPlaceStore.savedBy(id, placeId))
                .orElse(false);
    }

    @Bean
    MapPlaceQueryService mapPlaceQueryService(
            InMemoryMapPlaceStore store,
            MapSavedStateLookup savedStateLookup) {
        return new MapPlaceQueryService(store, savedStateLookup);
    }

    private MapPlaceProjection place(
            String placeId,
            String name,
            String category,
            String regionCode,
            String regionName,
            double lat,
            double lng,
            String summary,
            List<String> linkedOdiiStoryIds) {
        return new MapPlaceProjection(
                placeId,
                name,
                category,
                new MapRegionRef(regionCode, regionName, "CITY", "kr-45"),
                new MapCoordinates(lat, lng),
                "https://cdn.onmaru.example/places/" + placeId + "/cover.jpg",
                summary,
                linkedOdiiStoryIds,
                new MapDataAvailability(MapCoverageStatus.COMPLETE, MapCoverageStatus.PARTIAL, MapCoverageStatus.COMPLETE),
                MapPlaceStatus.PUBLIC);
    }
}
