package com.yrootlab.onmaru.admin.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminJtiHasherTests {

    @Test
    void hashesJtiAsStableLowercaseSha256WithoutRetainingOriginalValue() {
        assertThat(new AdminJtiHasher().hash("jti-1"))
                .isEqualTo("5964da055ae31cf8eb7230e190a504899c6f60f07f9f1cd3cade9fbf226e8c28")
                .doesNotContain("jti-1");
    }
}
