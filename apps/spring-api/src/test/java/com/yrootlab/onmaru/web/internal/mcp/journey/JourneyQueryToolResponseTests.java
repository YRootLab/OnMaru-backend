package com.yrootlab.onmaru.web.internal.mcp.journey;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JourneyQueryToolResponseTests {

    @Test
    void emptyPlacesAreReportedAsNoMatch() {
        var response = JourneyQueryToolResponse.of(List.of());

        assertThat(response.status()).isEqualTo("NO_MATCH");
        assertThat(response.places()).isEmpty();
        assertThat(response.schemaVersion()).isEqualTo("journey.query.tools.v1");
        assertThat(response.catalogVersion()).isEqualTo("journey.query.tools.v1");
    }

    @Test
    void nonEmptyPlacesAreReportedAsOk() {
        var response = JourneyQueryToolResponse.of(List.of(new ProjectedPlace("place-1", null, null, null, "PUBLIC")));

        assertThat(response.status()).isEqualTo("OK");
        assertThat(response.places()).hasSize(1);
    }
}
