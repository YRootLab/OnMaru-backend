package com.yrootlab.onmaru.web.place.detail;

import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemorySavedPlaceStateLookup;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
class CatalogDetailConfiguration {

    @Bean
    InMemoryPlaceDetailStore placeDetailStore() {
        var store = new InMemoryPlaceDetailStore();
        store.add(PlaceProjection.publicPlace(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.8151, 127.153),
                List.of(new ImageProjection(
                        "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                        "전주 한옥마을 골목")),
                "전통 한옥과 공예, 음식, 산책 코스를 한 번에 경험할 수 있는 공개 관광 장소입니다.",
                List.of("한옥 골목", "공예 체험", "야간 산책"),
                "odii-jeonju-hanok-village"));
        return store;
    }

    @Bean
    InMemorySavedPlaceStateLookup savedPlaceStateLookup() {
        return new InMemorySavedPlaceStateLookup();
    }

    @Bean
    PlaceDetailQueryService placeDetailQueryService(
            InMemoryPlaceDetailStore store,
            InMemorySavedPlaceStateLookup savedPlaceStateLookup) {
        return new PlaceDetailQueryService(store, savedPlaceStateLookup);
    }
}
