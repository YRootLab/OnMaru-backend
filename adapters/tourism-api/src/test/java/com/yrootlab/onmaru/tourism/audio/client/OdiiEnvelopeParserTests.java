package com.yrootlab.onmaru.tourism.audio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdiiEnvelopeParserTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OdiiEnvelopeParser parser = new OdiiEnvelopeParser(objectMapper);

    @Test
    void parsesSuccessArrayAndPagination() throws Exception {
        OdiiPage page = parser.parse("storyBasedList", fixture("story_based_first_page.json"));

        assertThat(page.items()).hasSize(3);
        assertThat(page.items().getFirst().stid()).isEqualTo("45");
        assertThat(page.items().getFirst().langCode()).isEqualTo("ko");
        assertThat(page.page()).isEqualTo(1);
        assertThat(page.pageSize()).isEqualTo(3);
        assertThat(page.totalCount()).isEqualTo(10);
        assertThat(page.lastPage()).isFalse();
    }

    @Test
    void acceptsEmptyStringItemsAsEmptyLastPage() throws Exception {
        OdiiPage page = parser.parse("storySearchList", fixture("empty_search.json"));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalCount()).isZero();
        assertThat(page.lastPage()).isTrue();
    }

    @Test
    void preservesEmptyScriptAndAudioForDownstreamProvenanceMapping() throws Exception {
        OdiiPage page = parser.parse("storySearchList", fixture("empty_script_story.json"));

        assertThat(page.items()).anySatisfy(item -> {
            assertThat(item.script()).isEmpty();
            assertThat(item.audioUrl()).isEmpty();
        });
    }

    @Test
    void rejectsProviderErrorEnvelope() throws Exception {
        assertThatThrownBy(() -> parser.parse(
                "storySearchList",
                fixture("provider_error_missing_key.json")
        ))
                .isInstanceOf(OdiiParseException.class)
                .hasMessageContaining("ODII_PROVIDER_ERROR");
    }

    @Test
    void acceptsSingletonItemAndRejectsPaginationDrift() throws Exception {
        var singletonItems = objectMapper.createObjectNode();
        singletonItems.set("item", item());
        var singletonBody = objectMapper.createObjectNode();
        singletonBody.set("items", singletonItems);
        singletonBody.put("numOfRows", 1);
        singletonBody.put("pageNo", 1);
        singletonBody.put("totalCount", 1);
        var singletonResponse = objectMapper.createObjectNode();
        singletonResponse.set("header", objectMapper.createObjectNode()
                .put("resultCode", "0000")
                .put("resultMsg", "OK"));
        singletonResponse.set("body", singletonBody);
        var singletonRoot = objectMapper.createObjectNode();
        singletonRoot.set("response", singletonResponse);
        byte[] singleton = objectMapper.writeValueAsBytes(singletonRoot);

        assertThat(parser.parse("storySearchList", singleton).items()).hasSize(1);

        var invalidBody = objectMapper.createObjectNode()
                .put("items", "")
                .put("numOfRows", 0)
                .put("pageNo", 0)
                .put("totalCount", 0);
        var invalidResponse = objectMapper.createObjectNode();
        invalidResponse.set("header", objectMapper.createObjectNode().put("resultCode", "0000"));
        invalidResponse.set("body", invalidBody);
        var invalidRoot = objectMapper.createObjectNode();
        invalidRoot.set("response", invalidResponse);
        byte[] invalid = objectMapper.writeValueAsBytes(invalidRoot);

        assertThatThrownBy(() -> parser.parse("storySearchList", invalid))
                .isInstanceOf(OdiiParseException.class)
                .hasMessageContaining("ODII_SCHEMA_DRIFT");
    }

    private com.fasterxml.jackson.databind.node.ObjectNode item() {
        return objectMapper.createObjectNode()
                .put("tid", "89")
                .put("tlid", "300")
                .put("stid", "562")
                .put("stlid", "1204")
                .put("title", "남산골 한옥마을-개요")
                .put("audioTitle", "한옥마을")
                .put("script", "공식 대본")
                .put("audioUrl", "https://example.com/audio.mp3")
                .put("imageUrl", "")
                .put("playTime", "105")
                .put("mapX", "126.9936798")
                .put("mapY", "37.559163")
                .put("langCode", "ko")
                .put("createdtime", "20150619173503")
                .put("modifiedtime", "20250609074606");
    }

    private byte[] fixture(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/odii/" + name)) {
            if (input == null) {
                throw new IOException("fixture not found: " + name);
            }
            return objectMapper.writeValueAsBytes(objectMapper.readTree(input).path("response").path("body"));
        }
    }
}
