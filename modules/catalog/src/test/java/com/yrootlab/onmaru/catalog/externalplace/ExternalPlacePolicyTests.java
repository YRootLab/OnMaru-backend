package com.yrootlab.onmaru.catalog.externalplace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalPlacePolicyTests {

    private final ExternalPlacePolicy policy = new ExternalPlacePolicy();

    @Test
    void normalizesClientAssertedKakaoPlace() {
        var normalized = policy.validate(new ExternalPlaceCandidate(
                ExternalPlaceProvider.KAKAO,
                "  123456789  ",
                "  대청댐  ",
                36.4952,
                127.4981));

        assertThat(normalized.externalId()).isEqualTo("123456789");
        assertThat(normalized.name()).isEqualTo("대청댐");
        assertThat(normalized.lat()).isEqualTo(36.4952);
        assertThat(normalized.lng()).isEqualTo(127.4981);
    }

    @Test
    void acceptsInclusiveKoreanServiceAreaEdges() {
        assertThat(policy.validate(candidate(32.0, 123.0))).isNotNull();
        assertThat(policy.validate(candidate(39.5, 132.0))).isNotNull();
    }

    @Test
    void rejectsCoordinatesOutsideKoreanMvpServiceArea() {
        assertThatThrownBy(() -> policy.validate(candidate(35.0, 140.0)))
                .isInstanceOfSatisfying(ExternalPlaceValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo("place.location");
                    assertThat(error.reason()).isEqualTo("OUTSIDE_SERVICE_AREA");
                });
    }

    @Test
    void rejectsNonFiniteAndInvalidWgs84Coordinates() {
        assertInvalidCoordinate(Double.NaN, 127.0);
        assertInvalidCoordinate(Double.POSITIVE_INFINITY, 127.0);
        assertInvalidCoordinate(91.0, 127.0);
        assertInvalidCoordinate(37.0, 181.0);
    }

    @Test
    void rejectsMissingProviderIdAndName() {
        assertRequired(new ExternalPlaceCandidate(null, "123", "장소", 37.0, 127.0), "place.provider");
        assertRequired(new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, " ", "장소", 37.0, 127.0),
                "place.externalId");
        assertRequired(new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, "123", " ", 37.0, 127.0),
                "place.name");
    }

    @Test
    void enforcesExternalIdAndNameCodePointLimits() {
        assertThat(policy.validate(new ExternalPlaceCandidate(
                ExternalPlaceProvider.KAKAO, "1".repeat(128), "가".repeat(100), 37.0, 127.0)))
                .isNotNull();

        assertTooLong(new ExternalPlaceCandidate(
                ExternalPlaceProvider.KAKAO, "1".repeat(129), "장소", 37.0, 127.0), "place.externalId");
        assertTooLong(new ExternalPlaceCandidate(
                ExternalPlaceProvider.KAKAO, "123", "가".repeat(101), 37.0, 127.0), "place.name");
    }

    private ExternalPlaceCandidate candidate(double lat, double lng) {
        return new ExternalPlaceCandidate(ExternalPlaceProvider.KAKAO, "123456789", "대청댐", lat, lng);
    }

    private void assertInvalidCoordinate(double lat, double lng) {
        assertThatThrownBy(() -> policy.validate(candidate(lat, lng)))
                .isInstanceOfSatisfying(ExternalPlaceValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo("place.location");
                    assertThat(error.reason()).isEqualTo("INVALID_COORDINATE");
                });
    }

    private void assertRequired(ExternalPlaceCandidate candidate, String field) {
        assertThatThrownBy(() -> policy.validate(candidate))
                .isInstanceOfSatisfying(ExternalPlaceValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.reason()).isEqualTo("REQUIRED");
                });
    }

    private void assertTooLong(ExternalPlaceCandidate candidate, String field) {
        assertThatThrownBy(() -> policy.validate(candidate))
                .isInstanceOfSatisfying(ExternalPlaceValidationException.class, error -> {
                    assertThat(error.field()).isEqualTo(field);
                    assertThat(error.reason()).isEqualTo("TOO_LONG");
                });
    }
}
