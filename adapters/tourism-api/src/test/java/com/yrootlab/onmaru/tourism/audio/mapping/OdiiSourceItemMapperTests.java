package com.yrootlab.onmaru.tourism.audio.mapping;

import com.yrootlab.onmaru.tourism.audio.client.OdiiSourceItem;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OdiiSourceItemMapperTests {

    @Test
    void copiesProviderFieldsIntoTheAudioOwnedSourceContract() {
        var item = new OdiiSourceItem(
                "storySearchList", "89", "300", "562", "1204", "story title", "audio title",
                "official script", "https://example.com/audio.mp3", "https://example.com/image.jpg",
                "105", "126.9936798", "37.559163", "ko", "20150619173503", "20250609074606"
        );

        var source = new OdiiSourceItemMapper().toSourceStory(item);

        assertThat(source.tid()).isEqualTo("89");
        assertThat(source.tlid()).isEqualTo("300");
        assertThat(source.stid()).isEqualTo("562");
        assertThat(source.stlid()).isEqualTo("1204");
        assertThat(source.langCode()).isEqualTo("ko");
        assertThat(source.script()).isEqualTo("official script");
        assertThat(source.modifiedTime()).isEqualTo("20250609074606");
    }
}
