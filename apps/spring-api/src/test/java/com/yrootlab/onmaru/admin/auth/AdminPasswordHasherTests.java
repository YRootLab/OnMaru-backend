package com.yrootlab.onmaru.admin.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AdminPasswordHasherTests {

    private final AdminPasswordHasher hasher = new AdminPasswordHasher();

    @Test
    void hashesAndVerifiesPasswordWithoutStoringThePlaintext() {
        String encoded = hasher.encode("correct horse battery staple");

        assertThat(encoded)
                .startsWith("$2")
                .doesNotContain("correct horse battery staple");
        assertThat(hasher.matches("correct horse battery staple", encoded)).isTrue();
        assertThat(hasher.matches("wrong password", encoded)).isFalse();
    }
}
