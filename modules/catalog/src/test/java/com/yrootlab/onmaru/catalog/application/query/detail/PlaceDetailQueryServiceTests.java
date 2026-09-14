package com.yrootlab.onmaru.catalog.application.query.detail;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PlaceDetailQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void returnsCanonicalPlaceDetailWithNullableProviderFieldsUnchanged() {
        var store = new InMemoryPlaceDetailStore();
        store.add(PlaceProjection.publicPlace(
                "p-null-provider-fields",
                "미상 한옥",
                "한옥",
                new RegionProjection("kr-11-seoul", "서울 종로구"),
                null,
                null,
                List.of(),
                "공개 가능한 canonical 장소입니다.",
                List.of(),
                null));
        var service = new PlaceDetailQueryService(store, savedByMe("p-null-provider-fields"));

        Optional<CanonicalPlaceDetail> detail = service.findCanonicalPlace(
                "p-null-provider-fields",
                Optional.of(MEMBER_ID));

        assertThat(detail).hasValueSatisfying(place -> {
            assertThat(place.address()).isNull();
            assertThat(place.coordinates()).isNull();
            assertThat(place.images()).isEmpty();
            assertThat(place.savedByMe()).isTrue();
        });
    }

    @Test
    void hidesNonPublicOrAmbiguousCanonicalMappings() {
        var store = new InMemoryPlaceDetailStore();
        store.add(PlaceProjection.hidden("p-hidden"));
        store.add(PlaceProjection.deleted("p-deleted"));
        store.add(PlaceProjection.ambiguous("p-ambiguous"));
        var service = new PlaceDetailQueryService(store, (memberId, placeId) -> false);

        assertThat(service.findCanonicalPlace("p-hidden", Optional.empty())).isEmpty();
        assertThat(service.findCanonicalPlace("p-deleted", Optional.empty())).isEmpty();
        assertThat(service.findCanonicalPlace("p-ambiguous", Optional.empty())).isEmpty();
        assertThat(service.findCanonicalPlace("p-missing", Optional.empty())).isEmpty();
    }

    @Test
    void returnsHanokDetailWithLinkedCardsUsingTheSameCanonicalPlaceId() {
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
        var service = new PlaceDetailQueryService(store, savedByMe("p-jeonju-hanok-village"));

        Optional<HanokDetail> detail = service.findHanok("p-jeonju-hanok-village", Optional.of(MEMBER_ID));

        assertThat(detail).hasValueSatisfying(hanok -> {
            assertThat(hanok.placeId()).isEqualTo("p-jeonju-hanok-village");
            assertThat(hanok.category()).isEqualTo(HanokCategory.HANOK);
            assertThat(hanok.savedByMe()).isTrue();
            assertThat(hanok.mapCard().placeId()).isEqualTo("p-jeonju-hanok-village");
            assertThat(hanok.mapCard().savedByMe()).isTrue();
            assertThat(hanok.odiiLinkedCard()).isNotNull();
            assertThat(hanok.odiiLinkedCard().placeId()).isEqualTo("p-jeonju-hanok-village");
        });
    }

    private SavedPlaceStateLookup savedByMe(String savedPlaceId) {
        Set<String> saved = Set.of(MEMBER_ID + ":" + savedPlaceId);
        return (memberId, placeId) -> memberId.map(id -> saved.contains(id + ":" + placeId)).orElse(false);
    }
}
