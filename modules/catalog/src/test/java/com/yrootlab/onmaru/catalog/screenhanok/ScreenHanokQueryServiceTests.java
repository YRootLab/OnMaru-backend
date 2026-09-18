package com.yrootlab.onmaru.catalog.screenhanok;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListCategory;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListProjection;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStatus;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenHanokQueryServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");

    @Test
    void joinsPublishedPlacementsWithLiveCatalogDataAndFiltersByMediaTypeAndRegion() {
        var hanoks = new InMemoryHanokListStore();
        hanoks.add(projection("p-001", "서천 이하복 고택", "충남"));
        hanoks.add(projection("p-002", "선교장", "강원"));
        var store = new InMemoryScreenHanokPlacementStore();
        store.publish(List.of(
                placement("p-001", ScreenHanokMediaType.K_DRAMA),
                placement("p-002", ScreenHanokMediaType.KPOP)));
        var service = new ScreenHanokQueryService(store, hanoks, (memberId, placeId) -> memberId.isPresent() && placeId.equals("p-001"));

        var dramaOnly = service.list(Optional.empty(), Optional.of(ScreenHanokMediaType.K_DRAMA), Optional.of(MEMBER_ID));

        assertThat(dramaOnly).hasSize(1);
        assertThat(dramaOnly.getFirst().placeId()).isEqualTo("p-001");
        assertThat(dramaOnly.getFirst().name()).isEqualTo("서천 이하복 고택");
        assertThat(dramaOnly.getFirst().savedByMe()).isTrue();

        var chungnamOnly = service.list(Optional.of("충남"), Optional.empty(), Optional.empty());
        assertThat(chungnamOnly).extracting(ScreenHanokEntry::placeId).containsExactly("p-001");
    }

    @Test
    void dropsPlacementsForPlacesNoLongerPublished() {
        var hanoks = new InMemoryHanokListStore();
        var store = new InMemoryScreenHanokPlacementStore();
        store.publish(List.of(placement("p-unpublished", ScreenHanokMediaType.CINEMA)));
        var service = new ScreenHanokQueryService(store, hanoks, (memberId, placeId) -> false);

        var entries = service.list(Optional.empty(), Optional.empty(), Optional.empty());

        assertThat(entries).isEmpty();
    }

    private static HanokListProjection projection(String placeId, String name, String regionName) {
        return new HanokListProjection(
                placeId, name, HanokListCategory.HANOK, "44", regionName, "https://img/" + placeId, "요약",
                List.of(), Instant.now(), HanokListStatus.PUBLIC);
    }

    private static ScreenHanokPlacement placement(String placeId, ScreenHanokMediaType mediaType) {
        return new ScreenHanokPlacement(
                placeId, mediaType, "작품명", "부제", List.of("#태그"), "https://example.com/" + placeId, "출처",
                Instant.now());
    }
}
