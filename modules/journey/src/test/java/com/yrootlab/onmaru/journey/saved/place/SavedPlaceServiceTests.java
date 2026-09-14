package com.yrootlab.onmaru.journey.saved.place;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SavedPlaceServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final Instant NOW = Instant.parse("2026-09-14T08:10:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void repeatedSaveKeepsOneRowAndReturnsCurrentSavedState() {
        var store = new InMemorySavedPlaceStore();
        var service = new SavedPlaceService(store, placeId -> true, clock, 500);

        SavedPlaceState first = service.save(MEMBER_ID, "p-jeonju-hanok-village");
        SavedPlaceState second = service.save(MEMBER_ID, "p-jeonju-hanok-village");

        assertThat(first).isEqualTo(second);
        assertThat(first.resourceType()).isEqualTo(SavedResourceType.PLACE);
        assertThat(first.resourceId()).isEqualTo("p-jeonju-hanok-village");
        assertThat(first.savedByMe()).isTrue();
        assertThat(first.savedAt()).isEqualTo(NOW);
        assertThat(store.countFor(MEMBER_ID, SavedResourceType.PLACE)).isEqualTo(1);
    }

    @Test
    void repeatedDeleteSucceedsWhenRowIsAlreadyAbsent() {
        var store = new InMemorySavedPlaceStore();
        var service = new SavedPlaceService(store, placeId -> true, clock, 500);
        service.save(MEMBER_ID, "p-jeonju-hanok-village");

        service.delete(MEMBER_ID, "p-jeonju-hanok-village");
        service.delete(MEMBER_ID, "p-jeonju-hanok-village");

        assertThat(store.savedBy(MEMBER_ID, "p-jeonju-hanok-village")).isFalse();
    }

    @Test
    void saveRevalidatesPublicEligibilityForEveryCommand() {
        var store = new InMemorySavedPlaceStore();
        var service = new SavedPlaceService(store, placeId -> false, clock, 500);

        assertThatThrownBy(() -> service.save(MEMBER_ID, "p-private-place"))
                .isInstanceOf(SavedPlaceNotFoundException.class);

        assertThat(store.savedBy(MEMBER_ID, "p-private-place")).isFalse();
    }

    @Test
    void saveFailsWhenMemberAlreadyReachedLimit() {
        var store = new InMemorySavedPlaceStore();
        var service = new SavedPlaceService(store, placeId -> true, clock, 1);
        service.save(MEMBER_ID, "p-jeonju-hanok-village");

        assertThatThrownBy(() -> service.save(MEMBER_ID, "p-gyeongju-gyochon"))
                .isInstanceOf(SavedPlaceLimitExceededException.class)
                .extracting("limit")
                .isEqualTo(1);
    }
}
