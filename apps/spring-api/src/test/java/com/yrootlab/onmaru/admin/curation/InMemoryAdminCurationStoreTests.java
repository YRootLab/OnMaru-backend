package com.yrootlab.onmaru.admin.curation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAdminCurationStoreTests {
    @Test
    void upsertIncrementsVersionForSamePlaceAndCategory() {
        var store = new InMemoryAdminCurationStore();
        var place = UUID.randomUUID();
        var admin = UUID.randomUUID();

        var first = store.upsert(place, "VILLAGE", true, List.of("EDITOR_PICK"), admin);
        var second = store.upsert(place, "VILLAGE", false, List.of(), admin);

        assertThat(second.version()).isEqualTo(first.version() + 1);
        assertThat(store.find("VILLAGE", false, 20)).containsExactly(second);
    }
}
