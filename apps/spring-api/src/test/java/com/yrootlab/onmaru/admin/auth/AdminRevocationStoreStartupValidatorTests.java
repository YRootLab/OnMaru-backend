package com.yrootlab.onmaru.admin.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminRevocationStoreStartupValidatorTests {

    @Test
    void productionRejectsInMemoryRevocationDelegate() {
        assertThatThrownBy(() -> new AdminRevocationStoreStartupValidator(
                new InMemoryAdminJtiRevocationStore()).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBC");
    }
}
