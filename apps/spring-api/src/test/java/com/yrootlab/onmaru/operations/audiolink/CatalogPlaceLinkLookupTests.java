package com.yrootlab.onmaru.operations.audiolink;

import com.yrootlab.onmaru.catalog.application.query.detail.CoordinatesProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.ImageProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.InMemoryPlaceDetailStore;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceDetailQueryService;
import com.yrootlab.onmaru.catalog.application.query.detail.PlaceProjection;
import com.yrootlab.onmaru.catalog.application.query.detail.RegionProjection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CatalogPlaceLinkLookupTests {

    private static final String PLACE_ID = "p-jeonju-hanok-village";
    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void hydratesTheApprovedLinkFromTheCurrentPublicCanonicalProjection() {
        var store = new InMemoryPlaceDetailStore();
        store.add(publicPlace());
        var lookup = new CatalogPlaceLinkLookup(new PlaceDetailQueryService(
                store,
                (memberId, placeId) -> memberId.filter(MEMBER_ID::equals).isPresent()));

        assertThat(lookup.findPublicPlace(PLACE_ID, Optional.of(MEMBER_ID)))
                .hasValueSatisfying(place -> {
                    assertThat(place.placeId()).isEqualTo(PLACE_ID);
                    assertThat(place.name()).isEqualTo("전주 한옥마을");
                    assertThat(place.category()).isEqualTo("한옥");
                    assertThat(place.regionName()).isEqualTo("전북 전주시");
                    assertThat(place.thumbnailUrl()).isEqualTo(
                            "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg");
                    assertThat(place.savedByMe()).isTrue();
                });
    }

    @Test
    void excludesDeletedHiddenAndAmbiguousCanonicalPlaces() {
        var store = new InMemoryPlaceDetailStore();
        store.add(PlaceProjection.deleted("p-deleted"));
        store.add(PlaceProjection.hidden("p-hidden"));
        store.add(PlaceProjection.ambiguous("p-ambiguous"));
        var lookup = new CatalogPlaceLinkLookup(new PlaceDetailQueryService(
                store,
                (memberId, placeId) -> false));

        assertThat(lookup.findPublicPlace("p-deleted", Optional.empty())).isEmpty();
        assertThat(lookup.findPublicPlace("p-hidden", Optional.empty())).isEmpty();
        assertThat(lookup.findPublicPlace("p-ambiguous", Optional.empty())).isEmpty();
        assertThat(lookup.findPublicPlace("p-missing", Optional.empty())).isEmpty();
    }

    private PlaceProjection publicPlace() {
        return PlaceProjection.publicPlace(
                PLACE_ID,
                "전주 한옥마을",
                "한옥",
                new RegionProjection("kr-45-jeonju", "전북 전주시"),
                "전북 전주시 완산구 기린대로 99",
                new CoordinatesProjection(35.8151, 127.153),
                List.of(new ImageProjection(
                        "https://cdn.onmaru.example/places/p-jeonju-hanok-village/cover.jpg",
                        "전주 한옥마을 골목")),
                "공개 canonical 장소",
                List.of("한옥 골목"),
                null);
    }
}
