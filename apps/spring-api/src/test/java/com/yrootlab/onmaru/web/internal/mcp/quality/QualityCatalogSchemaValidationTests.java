package com.yrootlab.onmaru.web.internal.mcp.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QualityCatalogSchemaValidationTests {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    @DisplayName("quality-tools.v1.json 카탈로그에 두 도구와 필수 스코프가 정의되어 있어야 한다")
    void assertsQualityToolsCatalogSchema() throws Exception {
        Path catalogPath = Paths.get("../../docs/contracts/mcp/quality-tools.v1.json");
        if (!Files.exists(catalogPath)) {
            catalogPath = Paths.get("docs/contracts/mcp/quality-tools.v1.json");
        }
        assertThat(Files.exists(catalogPath)).isTrue();

        JsonNode root = OBJECT_MAPPER.readTree(Files.readAllBytes(catalogPath));

        assertThat(root.path("catalogVersion").asText()).isEqualTo("quality.tools.v1");
        assertThat(root.path("protocol").asText()).isEqualTo("MCP");

        JsonNode tools = root.path("tools");
        assertThat(tools.isArray()).isTrue();
        assertThat(tools.size()).isEqualTo(2);

        Set<String> toolNames = new HashSet<>();
        for (JsonNode tool : tools) {
            toolNames.add(tool.path("name").asText());
        }

        assertThat(toolNames).containsExactlyInAnyOrder(
                "quality_get_run_observation",
                "quality_get_public_source_state"
        );
    }
}
