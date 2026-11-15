package com.yrootlab.onmaru.journey.region;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CanonicalRegionAliasesTests {

    @Test
    void provinceMapsToItsCatalogCode() {
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-26")).containsExactly("kr-datalab-26");
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-42")).containsExactly("kr-datalab-51");
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-11")).containsExactly("kr-11");
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-45")).containsExactly("kr-45");
    }

    @Test
    void sigunguCodeIsKeptAsIsBecauseTheCatalogUsesThatForm() {
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-11-jongno")).containsExactly("kr-11-jongno");
    }

    @Test
    void gwangjuAndJeonnamAreNotGuessed() {
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-29")).isEmpty();
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-46")).isEmpty();
        assertThat(CanonicalRegionAliases.isMappedProvince("kr-29")).isFalse();
    }

    @Test
    void unknownOrMalformedCodesMapToNothing() {
        assertThat(CanonicalRegionAliases.catalogCodesFor("kr-99")).isEqualTo(List.of());
        assertThat(CanonicalRegionAliases.catalogCodesFor("region-seoul")).isEmpty();
        assertThat(CanonicalRegionAliases.catalogCodesFor(null)).isEmpty();
    }
}
