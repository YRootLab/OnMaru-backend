package com.yrootlab.onmaru.audio.sync;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdiiSourceMapperTests {

    private final OdiiSourceMapper mapper = new OdiiSourceMapper();

    @Test
    void preservesProviderLanguageIdsInsteadOfCollapsingByTidAndStid() {
        var korean = mapper.map(source("300", "1204", "ko", "공식 대본"));
        var english = mapper.map(source("301", "2187", "en", "Official script"));

        assertThat(korean.spot().identity()).isNotEqualTo(english.spot().identity());
        assertThat(korean.story().identity()).isNotEqualTo(english.story().identity());
        assertThat(korean.story().identity().stid()).isEqualTo(english.story().identity().stid());
        assertThat(korean.story().transcriptProvenance()).isEqualTo(TranscriptProvenance.OFFICIAL);
    }

    @Test
    void emptyOfficialScriptAndAudioRemainMissing() {
        var mapped = mapper.map(source("412", "1520", "ko", "", "", ""));

        assertThat(mapped.story().transcriptProvenance()).isEqualTo(TranscriptProvenance.MISSING);
        assertThat(mapped.story().script()).isNull();
        assertThat(mapped.story().audioUrl()).isNull();
        assertThat(mapped.story().imageUrl()).isNull();
    }

    @Test
    void parsesDurationCoordinatesAndProviderTimestamp() {
        var mapped = mapper.map(source("300", "1204", "ko", "대본"));

        assertThat(mapped.story().durationSeconds()).isEqualTo(105);
        assertThat(mapped.spot().longitude()).isEqualByComparingTo(new BigDecimal("126.9936798"));
        assertThat(mapped.spot().latitude()).isEqualByComparingTo(new BigDecimal("37.559163"));
        assertThat(mapped.story().sourceModifiedAt()).hasToString("2025-06-09T07:46:06Z");
        assertThat(mapped.story().contentHash()).hasSize(64);
    }

    @Test
    void rejectsInvalidIdentityDurationCoordinatesAndLanguage() {
        assertThatThrownBy(() -> mapper.map(source("", "1204", "ko", "대본")))
                .isInstanceOf(OdiiMappingException.class)
                .hasMessageContaining("tlid");
        assertThatThrownBy(() -> mapper.map(source("300", "1204", "fr", "대본")))
                .isInstanceOf(OdiiMappingException.class)
                .hasMessageContaining("langCode");
        assertThatThrownBy(() -> mapper.map(new OdiiSourceStory(
                "89", "300", "562", "1204", "제목", "오디오 제목", "대본",
                "https://example.com/audio.mp3", "", "-1", "181", "37", "ko",
                "20150619173503", "20250609074606"
        )))
                .isInstanceOf(OdiiMappingException.class);
    }

    private OdiiSourceStory source(String tlid, String stlid, String language, String script) {
        return source(tlid, stlid, language, script,
                "https://example.com/audio.mp3", "https://example.com/image.jpg");
    }

    private OdiiSourceStory source(
            String tlid,
            String stlid,
            String language,
            String script,
            String audioUrl,
            String imageUrl
    ) {
        return new OdiiSourceStory(
                "89",
                tlid,
                "562",
                stlid,
                "남산골 한옥마을-개요",
                "조선시대의 생활상을 엿볼 수 있는 한옥마을",
                script,
                audioUrl,
                imageUrl,
                "105",
                "126.9936798",
                "37.559163",
                language,
                "20150619173503",
                "20250609074606"
        );
    }
}
