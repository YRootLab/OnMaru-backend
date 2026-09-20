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
        store.add(card(
                "p-gyeongbokgung",
                "경복궁",
                HanokListCategory.HISTORIC_SITE,
                "kr-11-jongno",
                "서울 종로구",
                null,
                "조선 왕조의 정궁과 궁궐 건축을 함께 만나는 대표 역사 문화유산입니다.",
                List.of("문화재", "궁궐", "역사", "산책"),
                Instant.parse("2026-09-14T06:00:00Z")));
        store.add(card(
                "p-changdeokgung",
                "창덕궁과 후원",
                HanokListCategory.HISTORIC_SITE,
                "kr-11-jongno",
                "서울 종로구",
                null,
                "궁궐의 건축과 자연 지형을 살린 후원을 함께 둘러보는 역사 코스입니다.",
                List.of("문화재", "궁궐", "정원"),
                Instant.parse("2026-09-14T05:00:00Z")));
        store.add(card(
                "p-suwon-hwaseong",
                "수원화성",
                HanokListCategory.HISTORIC_SITE,
                "kr-41-suwon",
                "경기 수원시",
                null,
                "성곽길과 행궁, 전통 마을을 연결해 걷기 좋은 세계문화유산입니다.",
                List.of("문화재", "성곽", "걷기", "역사"),
                Instant.parse("2026-09-14T04:00:00Z")));
        store.add(card(
                "p-andong-hahoe",
                "안동 하회마을",
                HanokListCategory.HANOK,
                "kr-47-andong",
                "경북 안동시",
                null,
                "낙동강 물길과 전통 가옥, 탈춤 문화를 함께 경험하는 민속 마을입니다.",
                List.of("한옥", "민속", "문화재", "체험"),
                Instant.parse("2026-09-14T03:00:00Z")));
        store.add(card(
                "p-nagan-eupseong",
                "순천 낙안읍성",
                HanokListCategory.HISTORIC_SITE,
                "kr-46-suncheon",
                "전남 순천시",
                null,
                "초가와 성곽이 남아 있는 마을에서 지역의 생활 문화를 만나는 곳입니다.",
                List.of("문화재", "민속", "성곽", "체험"),
                Instant.parse("2026-09-14T02:00:00Z")));
        store.add(card(
                "p-jeju-seongeup",
                "제주 성읍민속마을",
                HanokListCategory.HANOK_EXPERIENCE,
                "kr-50-jeju",
                "제주 서귀포시",
                null,
                "제주 전통 초가와 생활 문화를 체험하며 섬의 건축을 이해하는 마을입니다.",
                List.of("민속", "초가", "체험", "제주"),
                Instant.parse("2026-09-14T01:00:00Z")));
        store.add(card(
                "p-seoraksan-sokcho",
                "설악산 소공원",
                HanokListCategory.NATURE_SITE,
                "kr-28-sokcho",
                "강원 속초시",
                null,
                "산책과 자연 관찰을 중심으로 한옥 여행과 연결하기 좋은 생태 관광지입니다.",
                List.of("생태", "자연", "산책", "활동"),
                Instant.parse("2026-09-13T09:00:00Z")));
        store.add(card(
                "p-damyang-juknokwon",
                "담양 죽녹원",
                HanokListCategory.NATURE_SITE,
                "kr-46-damyang",
                "전남 담양군",
                null,
                "대숲 산책과 지역 전통 문화를 함께 즐기는 생태·휴식 공간입니다.",
                List.of("생태", "대나무", "산책", "휴식"),
                Instant.parse("2026-09-13T08:00:00Z")));
        store.add(card(
                "p-jeonju-traditional-experience",
                "전주 전통문화 체험관",
                HanokListCategory.LOCAL_SCENE,
                "kr-45-jeonju",
                "전북 전주시",
                null,
                "한지와 공예, 전통 음식을 직접 경험하며 한옥 여행을 완성하는 활동입니다.",
                List.of("체험", "공예", "음식", "활동"),
                Instant.parse("2026-09-13T07:00:00Z")));
        store.add(card(
                "p-jongmyo-shrine",
                "종묘",
                HanokListCategory.HISTORIC_SITE,
                "kr-11-jongno",
                "서울 종로구",
                null,
                "왕실 제례 공간과 전통 건축의 질서를 차분히 살펴볼 수 있는 세계유산입니다.",
                List.of("문화재", "세계유산", "역사"),
                Instant.parse("2026-09-12T12:00:00Z")));
        store.add(card(
                "p-bulguksa",
                "불국사",
                HanokListCategory.HISTORIC_SITE,
                "kr-47-gyeongju",
                "경북 경주시",
                null,
                "석굴암과 함께 신라 불교 건축과 유산을 만나는 대표 사찰입니다.",
                List.of("문화재", "사찰", "세계유산"),
                Instant.parse("2026-09-12T11:00:00Z")));
        store.add(card(
                "p-jongmyo-gwangju",
                "광주 양림동 역사문화마을",
                HanokListCategory.HISTORIC_SITE,
                "kr-29-gwangju",
                "광주 남구",
                null,
                "근대 문화유산과 오래된 골목을 걸으며 지역의 시간을 만나는 마을입니다.",
                List.of("역사", "골목", "문화"),
                Instant.parse("2026-09-12T10:00:00Z")));
        store.add(card(
                "p-suncheon-bay",
                "순천만습지",
                HanokListCategory.NATURE_SITE,
                "kr-46-suncheon",
                "전남 순천시",
                null,
                "갈대밭과 철새를 관찰하며 생태 여행을 즐길 수 있는 대표 습지입니다.",
                List.of("생태", "습지", "관찰"),
                Instant.parse("2026-09-12T09:00:00Z")));
        store.add(card(
                "p-jeju-bijarim",
                "제주 비자림",
                HanokListCategory.NATURE_SITE,
                "kr-50-jeju",
                "제주 제주시",
                null,
                "천년의 비자나무 숲길을 걸으며 제주 고유의 생태를 만나는 곳입니다.",
                List.of("생태", "숲", "제주", "산책"),
                Instant.parse("2026-09-12T08:00:00Z")));
        store.add(card(
                "p-taehwagang-ulsan",
                "울산 태화강 국가정원",
                HanokListCategory.NATURE_SITE,
                "kr-31-ulsan",
                "울산 중구",
                null,
                "십리대숲과 강변 정원을 따라 도심 속 생태 산책을 즐길 수 있습니다.",
                List.of("생태", "정원", "산책"),
                Instant.parse("2026-09-12T07:00:00Z")));
        store.add(card(
                "p-jeonju-nambu-market",
                "전주 남부시장",
                HanokListCategory.TRADITIONAL_MARKET,
                "kr-45-jeonju",
                "전북 전주시",
                null,
                "한옥마을과 함께 지역 음식과 야시장 문화를 즐기는 전통시장입니다.",
                List.of("시장", "음식", "야시장"),
                Instant.parse("2026-09-12T06:00:00Z")));
        store.add(card(
                "p-tongyeong-market",
                "통영 중앙전통시장",
                HanokListCategory.TRADITIONAL_MARKET,
                "kr-48-tongyeong",
                "경남 통영시",
                null,
                "항구의 풍경과 해산물, 지역 생활 문화를 함께 만나는 시장입니다.",
                List.of("시장", "바다", "음식"),
                Instant.parse("2026-09-12T05:00:00Z")));
        store.add(card(
                "p-jeonju-hanji",
                "전주 한지문화축제 체험",
                HanokListCategory.CULTURE_ART,
                "kr-45-jeonju",
                "전북 전주시",
                null,
                "한지 공예를 직접 만들며 전통 소재와 지역 장인 문화를 경험합니다.",
                List.of("체험", "공예", "한지"),
                Instant.parse("2026-09-12T04:00:00Z")));
        store.add(card(
                "p-gangneung-dano",
                "강릉 단오문화관",
                HanokListCategory.CULTURE_ART,
                "kr-42-gangneung",
                "강원 강릉시",
                null,
                "강릉단오제의 전통과 지역 민속 문화를 전시와 체험으로 만나는 공간입니다.",
                List.of("체험", "민속", "강릉"),
                Instant.parse("2026-09-12T03:00:00Z")));
        store.add(card(
                "p-boseong-tea",
                "보성 녹차밭 체험",
                HanokListCategory.TRADITIONAL_FOOD,
                "kr-46-boseong",
                "전남 보성군",
                null,
                "차밭 산책과 다도 체험을 결합해 남도 여행을 즐길 수 있습니다.",
                List.of("체험", "차", "산책"),
                Instant.parse("2026-09-12T02:00:00Z")));
        store.add(card(
                "p-gongju-gongsanseong",
                "공주 공산성",
                HanokListCategory.HISTORIC_SITE,
                "kr-44-gongju",
                "충남 공주시",
                null,
                "백제 고도의 성곽길과 금강 풍경을 함께 만나는 역사 산책 코스입니다.",
                List.of("문화재", "성곽", "백제", "걷기"),
                Instant.parse("2026-09-12T01:00:00Z")));
        store.add(card(
                "p-andong-gu-market",
                "안동 구시장",
                HanokListCategory.TRADITIONAL_MARKET,
                "kr-47-andong",
                "경북 안동시",
                null,
                "찜닭과 제례 음식, 지역 상인 문화를 한옥 마을과 함께 경험하는 전통시장입니다.",
                List.of("시장", "음식", "안동"),
                Instant.parse("2026-09-12T00:00:00Z")));
        store.add(card(
                "p-jeonju-hanok-stay",
                "전주 한옥스테이",
                HanokListCategory.HANOK_STAY,
                "kr-45-jeonju",
                "전북 전주시",
                null,
                "전통 한옥의 구조와 마당을 보존한 숙박 공간에서 하루를 머물 수 있습니다.",
                List.of("한옥", "숙박", "전주"),
                Instant.parse("2026-09-11T23:00:00Z")));
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
