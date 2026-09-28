package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.audio.query.OdiiPublicAudioUrlPolicy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiStoryConfigurationTests {

    @Test
    void defaultSettingsAllowTheVerifiedKtoAudioCdnOnly() {
        var settings = new OdiiStoryConfiguration.OdiiStorySettings(Set.of(""), null, null);
        var policy = new OdiiPublicAudioUrlPolicy(settings.publicHosts());

        assertThat(policy.allows(
                "https://sfj608538-sfj608538.ktcdn.co.kr/story/audio.mp3"))
                .isTrue();
        assertThat(policy.allows("https://untrusted.example/story/audio.mp3"))
                .isFalse();
    }
}
