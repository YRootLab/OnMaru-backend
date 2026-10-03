package com.yrootlab.onmaru.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JacksonCoreSecurityVersionTests {

    @Test
    void usesPatchedJacksonCoreVersionsAtRuntime() {
        assertThat(com.fasterxml.jackson.core.json.PackageVersion.VERSION.toFullString())
                .contains("2.21.7");
        assertThat(tools.jackson.core.json.PackageVersion.VERSION.toFullString())
                .contains("3.1.7");
    }
}
