package com.yrootlab.onmaru.web.hanok.list;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListQueryService;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.journey.saved.place.InMemorySavedPlaceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.List;

@Configuration
class HanokListConfiguration {

    @Bean
    InMemoryHanokListStore hanokListStore() {
        var store = new InMemoryHanokListStore();
        store.add(card(
                "p-jeonju-hanok-village",
                "전주 한옥마을",
                HanokListCategory.HANOK,
                "kr-45-jeonju",
                "전북 전주시",
                "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                "한옥 골목과 전통 체험을 함께 둘러볼 수 있는 대표 한옥 권역입니다.",
                List.of("한옥", "체험", "산책"),
                Instant.parse("2026-09-14T09:00:00Z")));
        store.add(card(
                "p-bukchon-hanok-cafe",
                "북촌 한옥 찻집",
                HanokListCategory.HANOK_CAFE,
                "kr-11-jongno",
                "서울 종로구",
                null,
                "한옥 구조를 보존한 조용한 찻집입니다.",
                List.of("카페", "북촌"),
                Instant.parse("2026-09-14T08:00:00Z")));
        store.add(card(
                "p-gyeongju-gyochon",
                "경주 교촌 한옥마을",
                HanokListCategory.HANOK,
                "kr-47-gyeongju",
                "경북 경주시",
                "https://cdn.onmaru.example/places/p-gyeongju-gyochon/cover.jpg",
                "월정교와 함께 둘러보기 좋은 전통 한옥 권역입니다.",
                List.of("한옥", "경주"),
                Instant.parse("2026-09-14T07:00:00Z")));
        return store;
    }

    @Bean
    HanokSavedStateLookup hanokSavedStateLookup(InMemorySavedPlaceStore savedPlaceStore) {
        return (memberId, placeId) -> memberId
                .map(id -> savedPlaceStore.savedBy(id, placeId))
                .orElse(false);
    }

    @Bean
    HanokListQueryService hanokListQueryService(
            InMemoryHanokListStore hanokListStore,
            HanokSavedStateLookup savedStateLookup) {
        return new HanokListQueryService(hanokListStore, savedStateLookup);
    }

    private HanokListProjection card(
            String placeId,
            String name,
            HanokListCategory category,
            String regionCode,
            String regionName,
            String thumbnailUrl,
            String summary,
            List<String> tags,
            Instant publishedAt) {
        return new HanokListProjection(
                placeId,
                name,
                category,
                regionCode,
                regionName,
                thumbnailUrl,
                summary,
                tags,
                publishedAt,
                HanokListStatus.PUBLIC);
    }
}
