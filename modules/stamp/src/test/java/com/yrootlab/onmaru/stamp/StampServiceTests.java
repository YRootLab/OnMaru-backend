package com.yrootlab.onmaru.stamp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StampServiceTests {

    private static final UUID MEMBER_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final Instant DAYTIME = Instant.parse("2026-09-26T02:30:00Z");

    @Test
    void rejectsNonFiniteOrOutOfRangeCoordinatesBeforeLookup() {
        var lookup = new FakePlaceLookup();
        var service = service(lookup, new InMemoryStampStore(), DAYTIME);

        assertThatThrownBy(() -> service.checkIn(MEMBER_ID,
                new CheckInCommand("p-test", Double.NaN, 127.0, 20)))
                .isInstanceOf(CheckInInputInvalidException.class);
        assertThatThrownBy(() -> service.checkIn(MEMBER_ID,
                new CheckInCommand("p-test", 91, 127.0, 20)))
                .isInstanceOf(CheckInInputInvalidException.class);
        assertThat(lookup.calls).isZero();
    }

    @Test
    void rejectsLowAccuracyAndOutsideRadius() {
        var lookup = new FakePlaceLookup();
        lookup.put("p-near", "kr-11-jongno", 301);
        var service = service(lookup, new InMemoryStampStore(), DAYTIME);

        assertThatThrownBy(() -> service.checkIn(MEMBER_ID,
                new CheckInCommand("p-near", 37.5, 127.0, 100.1)))
                .isInstanceOf(LocationAccuracyTooLowException.class);
        assertThatThrownBy(() -> service.checkIn(MEMBER_ID,
                new CheckInCommand("p-near", 37.5, 127.0, 100)))
                .isInstanceOf(OutsideCheckInRadiusException.class);
    }

    @Test
    void acceptsTheUncertaintyAdjustedBoundaryAndAwardsRegionalStamp() {
        var lookup = new FakePlaceLookup();
        lookup.put("p-bukchon", "kr-11-jongno", 300);
        var service = service(lookup, new InMemoryStampStore(), DAYTIME);

        var result = service.checkIn(MEMBER_ID,
                new CheckInCommand("p-bukchon", 37.5826, 126.9831, 100));

        assertThat(result.checkIn().distanceMeters()).isEqualTo(300);
        assertThat(result.checkIn().alreadyCheckedIn()).isFalse();
        assertThat(result.newAwards()).extracting(StampAwardSummary::code)
                .containsExactly("stamp_bukchon");
    }

    @Test
    void convergesSamePlaceAndBucketWithoutRepeatingAwards() {
        var lookup = new FakePlaceLookup();
        lookup.put("p-bukchon", "kr-11-jongno", 50);
        var service = service(lookup, new InMemoryStampStore(), DAYTIME);

        var first = service.checkIn(MEMBER_ID,
                new CheckInCommand("p-bukchon", 37.5826, 126.9831, 20));
        var repeated = service.checkIn(MEMBER_ID,
                new CheckInCommand("p-bukchon", 37.5826, 126.9831, 20));

        assertThat(repeated.checkIn().id()).isEqualTo(first.checkIn().id());
        assertThat(repeated.checkIn().alreadyCheckedIn()).isTrue();
        assertThat(repeated.newAwards()).isEmpty();
    }

    @Test
    void awardsNightStampUsingServerTimeInKorea() {
        var lookup = new FakePlaceLookup();
        lookup.put("p-bukchon", "kr-11-jongno", 20);
        var nightInKorea = Instant.parse("2026-09-26T10:00:00Z");
        var service = service(lookup, new InMemoryStampStore(), nightInKorea);

        var result = service.checkIn(MEMBER_ID,
                new CheckInCommand("p-bukchon", 37.5826, 126.9831, 10));

        assertThat(result.newAwards()).extracting(StampAwardSummary::code)
                .containsExactly("stamp_bukchon", "stamp_night_hanok");
    }

    @Test
    void awardsNationalStampOnlyAfterFiveDistinctRegionGroups() {
        var lookup = new FakePlaceLookup();
        lookup.put("p-bukchon", "kr-11-jongno", 10);
        lookup.put("p-suwon", "kr-41-suwon", 10);
        lookup.put("p-gangneung", "kr-42-gangneung", 10);
        lookup.put("p-asan", "kr-44-asan", 10);
        lookup.put("p-jeonju", "kr-45-jeonju", 10);
        var store = new InMemoryStampStore();

        for (var placeId : new String[]{"p-bukchon", "p-suwon", "p-gangneung", "p-asan"}) {
            service(lookup, store, DAYTIME).checkIn(MEMBER_ID,
                    new CheckInCommand(placeId, 37.0, 127.0, 10));
        }
        assertThat(service(lookup, store, DAYTIME).book(MEMBER_ID).items())
                .filteredOn(StampBookItem::collected)
                .extracting(StampBookItem::code)
                .doesNotContain("stamp_national_master");

        var fifth = service(lookup, store, DAYTIME).checkIn(MEMBER_ID,
                new CheckInCommand("p-jeonju", 35.8, 127.1, 10));

        assertThat(fifth.newAwards()).extracting(StampAwardSummary::code)
                .contains("stamp_jeonju", "stamp_national_master");
        assertThat(fifth.summary().visitedRegionCount()).isEqualTo(5);
    }

    private StampService service(FakePlaceLookup lookup, InMemoryStampStore store, Instant now) {
        return new StampService(lookup, store, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static final class FakePlaceLookup implements CheckInPlaceLookup {
        private final Map<String, VerifiedPlace> places = new HashMap<>();
        private int calls;

        void put(String publicId, String regionCode, int distanceMeters) {
            places.put(publicId, new VerifiedPlace(UUID.randomUUID(), publicId, regionCode, distanceMeters));
        }

        @Override
        public Optional<VerifiedPlace> verify(String placeId, double latitude, double longitude) {
            calls++;
            return Optional.ofNullable(places.get(placeId));
        }
    }
}
