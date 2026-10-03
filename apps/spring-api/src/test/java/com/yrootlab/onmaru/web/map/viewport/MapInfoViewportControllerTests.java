package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import org.junit.jupiter.api.Test;
import com.yrootlab.onmaru.web.map.MapInfoRequestExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
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
                .andExpect(jsonPath("$.totalCountInViewport").value(0))
                .andExpect(jsonPath("$.appliedCategories[0]").value("HANOK"))
                .andExpect(jsonPath("$.projection.projectionName").value("map_place_read_projection"))
                .andExpect(jsonPath("$.servedBbox.west").value(126.8));
    }

    @Test
    void acceptsHanokAsAPublicViewportCategory() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliedCategories[0]").value("HANOK"))
                .andExpect(jsonPath("$.appliedCategories[1]").value("HANOK_STAY"))
                .andExpect(jsonPath("$.appliedCategories[2]").value("HANOK_CAFE"))
                .andExpect(jsonPath("$.appliedCategories[3]").value("HANOK_EXPERIENCE"));
    }

    @Test
    void keepsTheKakaoLevelRenderModeBoundaries() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("PLACE"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("PLACE"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "6"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("CLUSTER"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("CLUSTER"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "11"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("REGION"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("REGION"));
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

    @Test
    void returnsServiceUnavailableWhenTheApiTimeoutBudgetIsExceeded() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MapInfoViewportStore slowStore = query -> {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new InMemoryMapInfoViewportStore().find(query);
            };
            var controller = new MapInfoViewportController(
                    new MapInfoViewportQueryService(slowStore),
                    (MeterRegistry) null,
                    new MapInfoRequestExecutor(Duration.ofMillis(20), executor));
            var timeoutMvc = MockMvcBuilders.standaloneSetup(controller).build();

            timeoutMvc.perform(get("/api/v1/map/info/viewport")
                            .param("bbox", "126.8,35.0,127.2,36.0")
                            .param("zoomLevel", "8"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
        }
    }
}
