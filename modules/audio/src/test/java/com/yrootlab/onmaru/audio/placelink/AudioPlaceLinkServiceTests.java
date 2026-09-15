package com.yrootlab.onmaru.audio.placelink;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AudioPlaceLinkServiceTests {

    private static final String SPOT_ID = "odii-spot-jeonju";
    private static final String PLACE_ID = "p-jeonju-hanok-village";
    private static final String SAME_NAME_PLACE_ID = "p-jeonju-hanok-village-east";
    private static final UUID MEMBER_ID = UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712");
    private static final Instant REVIEWED_AT = Instant.parse("2026-09-15T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(REVIEWED_AT, ZoneOffset.UTC);

    @Test
    void keepsSameNameAndCoordinateMissingCandidatesOutOfThePublicProjection() {
        var places = new FakeCanonicalPlaceLookup();
        places.publish(place(PLACE_ID, "전주 한옥마을", false));
        places.publish(place(SAME_NAME_PLACE_ID, "전주 한옥마을", false));
        var service = service(places);

        AudioPlaceLinkCandidate nameOnly = service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkMatchMethod.NAME_ONLY,
                new BigDecimal("0.91")));
        AudioPlaceLinkCandidate sameName = service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                SAME_NAME_PLACE_ID,
                AudioPlaceLinkMatchMethod.NAME_DISTANCE_ONLY,
                new BigDecimal("0.93")));

        assertThat(nameOnly.reviewStatus()).isEqualTo(AudioPlaceLinkReviewStatus.PENDING);
        assertThat(sameName.reviewStatus()).isEqualTo(AudioPlaceLinkReviewStatus.PENDING);
        assertThat(service.findApprovedPlace(SPOT_ID, Optional.of(MEMBER_ID))).isEmpty();
    }

    @Test
    void exposesOnlyTheExplicitlyApprovedCandidateAsAHydratedCanonicalPlaceCard() {
        var places = new FakeCanonicalPlaceLookup();
        places.publish(place(PLACE_ID, "전주 한옥마을", true));
        places.publish(place(SAME_NAME_PLACE_ID, "전주 한옥마을", false));
        var service = service(places);
        service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkMatchMethod.NAME_DISTANCE_ONLY,
                new BigDecimal("0.95")));
        service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                SAME_NAME_PLACE_ID,
                AudioPlaceLinkMatchMethod.NAME_ONLY,
                new BigDecimal("0.80")));

        AudioPlaceLinkCandidate approved = service.review(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkReviewDecision.APPROVE);

        assertThat(approved.reviewStatus()).isEqualTo(AudioPlaceLinkReviewStatus.APPROVED);
        assertThat(approved.reviewedAt()).isEqualTo(REVIEWED_AT);
        assertThat(service.candidates(SPOT_ID))
                .extracting(AudioPlaceLinkCandidate::placeId, AudioPlaceLinkCandidate::reviewStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(PLACE_ID, AudioPlaceLinkReviewStatus.APPROVED),
                        org.assertj.core.groups.Tuple.tuple(
                                SAME_NAME_PLACE_ID,
                                AudioPlaceLinkReviewStatus.REJECTED));
        assertThat(service.findApprovedPlace(SPOT_ID, Optional.of(MEMBER_ID)))
                .hasValueSatisfying(link -> {
                    assertThat(link.spotId()).isEqualTo(SPOT_ID);
                    assertThat(link.matchMethod()).isEqualTo(AudioPlaceLinkMatchMethod.NAME_DISTANCE_ONLY);
                    assertThat(link.confidence()).isEqualByComparingTo("0.95");
                    assertThat(link.verifiedAt()).isEqualTo(REVIEWED_AT);
                    assertThat(link.place().placeId()).isEqualTo(PLACE_ID);
                    assertThat(link.place().name()).isEqualTo("전주 한옥마을");
                    assertThat(link.place().savedByMe()).isTrue();
                });
    }

    @Test
    void doesNotApproveAnUnavailableCanonicalPlace() {
        var service = service(new FakeCanonicalPlaceLookup());
        service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                null));

        assertThatThrownBy(() -> service.review(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkReviewDecision.APPROVE))
                .isInstanceOf(AudioPlaceLinkTargetUnavailableException.class);
        assertThat(service.candidates(SPOT_ID).getFirst().reviewStatus())
                .isEqualTo(AudioPlaceLinkReviewStatus.PENDING);
    }

    @Test
    void removesAnApprovedLinkFromProjectionWhenTheCanonicalPlaceIsDeleted() {
        var places = new FakeCanonicalPlaceLookup();
        places.publish(place(PLACE_ID, "전주 한옥마을", false));
        var service = service(places);
        service.submitCandidate(new AudioPlaceLinkCandidateCommand(
                SPOT_ID,
                PLACE_ID,
                AudioPlaceLinkMatchMethod.MANUAL_REFERENCE,
                null));
        service.review(SPOT_ID, PLACE_ID, AudioPlaceLinkReviewDecision.APPROVE);

        places.remove(PLACE_ID);

        assertThat(service.findApprovedPlace(SPOT_ID, Optional.empty())).isEmpty();
    }

    private AudioPlaceLinkService service(CanonicalPlaceLinkLookup places) {
        return new AudioPlaceLinkService(new InMemoryAudioPlaceLinkStore(), places, CLOCK);
    }

    private CanonicalPlaceLinkCard place(String placeId, String name, boolean savedByMe) {
        return new CanonicalPlaceLinkCard(
                placeId,
                name,
                "HANOK",
                "전북 전주시",
                "https://cdn.onmaru.example/places/" + placeId + "/cover.jpg",
                savedByMe);
    }

    private static final class FakeCanonicalPlaceLookup implements CanonicalPlaceLinkLookup {

        private final Map<String, CanonicalPlaceLinkCard> places = new HashMap<>();

        @Override
        public Optional<CanonicalPlaceLinkCard> findPublicPlace(
                String placeId,
                Optional<UUID> memberId) {
            return Optional.ofNullable(places.get(placeId));
        }

        void publish(CanonicalPlaceLinkCard place) {
            places.put(place.placeId(), place);
        }

        void remove(String placeId) {
            places.remove(placeId);
        }
    }
}
