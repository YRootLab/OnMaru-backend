package com.yrootlab.onmaru.config.secrets;

import java.util.Optional;

public final class FakeSecretProvider implements SecretProvider {

    @Override
    public SecretBundle get(String name) {
        String normalized = name.replace(".", "-");
        return new SecretBundle(
                name,
                "fake-" + normalized + "-current",
                Optional.of("fake-" + normalized + "-previous"));
    }
}
