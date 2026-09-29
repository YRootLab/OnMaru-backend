package com.yrootlab.onmaru.admin.pipeline;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryAdminPipelinePortTests {
    @Test
    void exposesSafeMissingStatusAndAcceptedRunInNonProduction() {
        var port = new InMemoryAdminPipelinePort();

        assertThat(port.status("kto-korean-tour").status()).isEqualTo("MISSING");
        assertThat(port.run("kto-korean-tour").status()).isEqualTo("QUEUED");
    }
}
