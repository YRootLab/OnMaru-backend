package com.yrootlab.onmaru.audio.query;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiPublicAudioUrlPolicyTests {

    private final OdiiPublicAudioUrlPolicy policy =
            new OdiiPublicAudioUrlPolicy(Set.of("cdn.onmaru.example"));

    @Test
    void allowsOnlyExactHttpsHostsWithoutCredentialsOrUrlSecrets() {
        assertThat(policy.allows("https://cdn.onmaru.example/audio/story.mp3")).isTrue();
        assertThat(policy.allows("HTTP://cdn.onmaru.example/audio/story.mp3")).isFalse();
        assertThat(policy.allows("https://cdn.onmaru.example.attacker.test/story.mp3")).isFalse();
        assertThat(policy.allows("https://user@cdn.onmaru.example/story.mp3")).isFalse();
        assertThat(policy.allows("https://cdn.onmaru.example/story.mp3?serviceKey=secret")).isFalse();
        assertThat(policy.allows("https://cdn.onmaru.example/story.mp3#secret")).isFalse();
        assertThat(policy.allows(null)).isFalse();
    }
}
