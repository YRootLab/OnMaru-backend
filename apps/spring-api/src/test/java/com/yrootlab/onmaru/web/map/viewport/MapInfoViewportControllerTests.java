package com.yrootlab.onmaru.web.map.viewport;

import com.yrootlab.onmaru.catalog.application.query.mapinfo.InMemoryMapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportStore;
import com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportQueryService;
import org.junit.jupiter.api.Test;
import com.yrootlab.onmaru.web.map.MapInfoRequestExecutor;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;

class MapInfoViewportControllerTests {

    @Test
    void rejectsMissingBlankAndAllCategories() throws Exception {
        for (var request : java.util.List.of(
                get("/api/v1/map/info/viewport").param("bbox", "126.8,35.0,127.2,36.0").param("zoomLevel", "5"),
                get("/api/v1/map/info/viewport").param("bbox", "126.8,35.0,127.2,36.0").param("zoomLevel", "5").param("category", " "),
                get("/api/v1/map/info/viewport").param("bbox", "126.8,35.0,127.2,36.0").param("zoomLevel", "5").param("category", "ALL"))) {
            mockMvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.details.field").value("category"));
        }
    }

    @Test
    void capsRequestedViewportMarkersAtSixty() throws Exception {
        var observedLimit = new AtomicInteger();
        MapInfoViewportStore store = query -> {
            observedLimit.set(query.limit());
            return new InMemoryMapInfoViewportStore().find(query);
        };
        var boundedMvc = MockMvcBuilders.standaloneSetup(
                new MapInfoViewportController(new MapInfoViewportQueryService(store))).build();

        boundedMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "HANOK")
                        .param("limit", "500"))
                .andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertEquals(60, observedLimit.get());
    }

    @Test
    void rejectsZeroLimitInsteadOfUsingTheLegacyFiveHundredMarkerDefault() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("limit"));
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new MapInfoViewportController(new MapInfoViewportQueryService(new InMemoryMapInfoViewportStore())))
            .build();

    @Test
    void returnsTheStableViewportEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.0"))
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"))
                .andExpect(jsonPath("$.profileVersion").value("map-zoom-v1"))
                .andExpect(jsonPath("$.totalCountInViewport").value(0))
                .andExpect(jsonPath("$.appliedCategories[0]").value("HANOK"))
                .andExpect(jsonPath("$.projection.projectionName").value("map_place_read_projection"))
                .andExpect(jsonPath("$.servedBbox.west").value(126.8))
                .andExpect(jsonPath("$.snapshotId").value("00000000-0000-0000-0000-000000000000"));
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
    void serializesNullableSnapshotIdExplicitly() throws Exception {
        MapInfoViewportStore store = query -> {
            var base = new InMemoryMapInfoViewportStore().find(query);
            return new com.yrootlab.onmaru.catalog.application.query.mapinfo.MapInfoViewportResponse(
                    base.schemaVersion(), base.renderMode(), base.profileVersion(), null,
                    base.totalCountInViewport(), base.items(), base.appliedCategories(),
                    base.coverage(), base.servedBbox(), base.projection());
        };
        var mvc = MockMvcBuilders.standaloneSetup(
                new MapInfoViewportController(new MapInfoViewportQueryService(store))).build();
        mvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(nullValue()));
    }

    @Test
    void keepsTheKakaoLevelRenderModeBoundaries() throws Exception {
        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "1")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("PLACE"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "5")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("PLACE"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "6")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("CLUSTER"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "7")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("CLUSTER"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "8")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "10")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("DISTRICT"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "11")
                        .param("category", "HANOK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renderMode").value("REGION"));

        mockMvc.perform(get("/api/v1/map/info/viewport")
                        .param("bbox", "126.8,35.0,127.2,36.0")
                        .param("zoomLevel", "14")
                        .param("category", "HANOK"))
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
                            .param("zoomLevel", "8")
                            .param("category", "HANOK"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("CATALOG_UNAVAILABLE"));
        }
    }

    @Test
    void timeoutLogIncludesViewportDiagnostics() throws Exception {
        var logger = (Logger) LoggerFactory.getLogger(MapInfoViewportController.class);
        var appender = new ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            MapInfoViewportStore slowStore = query -> {
                try { Thread.sleep(250); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                return new InMemoryMapInfoViewportStore().find(query);
            };
            var controller = new MapInfoViewportController(
                    new MapInfoViewportQueryService(slowStore), (MeterRegistry) null,
                    new MapInfoRequestExecutor(Duration.ofMillis(20), executor));
            var mvc = MockMvcBuilders.standaloneSetup(controller).build();
            mvc.perform(get("/api/v1/map/info/viewport")
                            .param("bbox", "126.8,35.0,127.2,36.0")
                            .param("zoomLevel", "9")
                            .param("category", "HANOK"))
                    .andExpect(status().isServiceUnavailable());
            assertThat(appender.list).anySatisfy(event -> {
                var message = event.getFormattedMessage();
                assertThat(message).contains("renderMode=DISTRICT", "zoomLevel=9",
                        "bbox=126.8,35.0,127.2,36.0", "category=HANOK",
                        "durationMs=", "itemCount=-1");
            });
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
