package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MapInfoViewportControllerTests {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new MapInfoViewportController(new MapInfoViewportQueryService(new InMemoryMapInfoViewportStore())))
            .build();

    @Test
    void returnsTheStableViewportEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "ALL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"))
                .andExpect(jsonPath("$.profileVersion").value("map-zoom-v1"))
                .andExpect(jsonPath("$.servedBbox.west").value(126.8));
    }

    @Test
    void rejectsMalformedBboxAsInvalidRequest() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2")
                        .param("zoomLevel", "8"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.details.field").value("bbox"));
    }
}
