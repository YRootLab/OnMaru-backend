package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class InsightsWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void listsObservationsWithBasisDateSpatialLevelUnitAndStatus() throws Exception {
        mockMvc.perform(get("/api/v1/insights/observations")
                        .param("regionCode", "kr-45-jeonju")
                        .param("metric", "VISITOR_COUNT")
                        .param("from", "2026-09-14")
                        .param("to", "2026-09-14"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.coverageStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.items[0].observedDate").value("2026-09-14"))
                .andExpect(jsonPath("$.items[0].metric").value("VISITOR_COUNT"))
                .andExpect(jsonPath("$.items[0].value").value(18240))
                .andExpect(jsonPath("$.items[0].unit").value("persons"))
                .andExpect(jsonPath("$.items[0].spatialLevel").value("SIGUNGU"))
                .andExpect(jsonPath("$.items[0].coverageStatus").value("COMPLETE"));
    }

    @Test
    void heatmapReturnsMissingCoverageWithoutZeroSynthesis() throws Exception {
        mockMvc.perform(get("/api/v1/insights/heatmap")
                        .param("regionCode", "kr-45-muju")
                        .param("date", "2026-09-14")
                        .param("metric", "CONGESTION_SCORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.coverageStatus").value("MISSING"))
                .andExpect(jsonPath("$.metric").value("CONGESTION_SCORE"))
                .andExpect(jsonPath("$.observedDate").value("2026-09-14"))
                .andExpect(jsonPath("$.spots").isEmpty());
    }

    @Test
    void heatmapReturnsTargetConcentrationSpots() throws Exception {
        mockMvc.perform(get("/api/v1/insights/heatmap")
                        .param("regionCode", "kr-45-jeonju")
                        .param("date", "2026-09-14")
                        .param("metric", "CONGESTION_SCORE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.coverageStatus").value("COMPLETE"))
                .andExpect(jsonPath("$.spots[0].visitorCount").value(18240))
                .andExpect(jsonPath("$.spots[0].congestionScore").value(72.4))
                .andExpect(jsonPath("$.spots[0].coverageStatus").value("COMPLETE"));
    }

    @Test
    void heatmapAcceptsFrontendVisitCountMetricAndReturnsDerivedMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/insights/heatmap")
                        .param("regionCode", "kr-45-jeonju")
                        .param("date", "2026-09-14")
                        .param("metric", "VISIT_COUNT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.metric").value("VISIT_COUNT"))
                .andExpect(jsonPath("$.origin").value("DERIVED_INDEX"))
                .andExpect(jsonPath("$.spatialLevel").value("SIGUNGU"))
                .andExpect(jsonPath("$.observedFrom").value("2026-09-14"))
                .andExpect(jsonPath("$.observedTo").value("2026-09-14"))
                .andExpect(jsonPath("$.methodologyVersion").value("warmth-v2"))
                .andExpect(jsonPath("$.spots").isNotEmpty());
    }

    @Test
    void mapHeatPathReturnsTheFrontendViewportContract() throws Exception {
        mockMvc.perform(get("/api/map/heat")
                        .param("lat", "35.8151")
                        .param("lng", "127.1530")
                        .param("level", "7")
                        .param("radius", "15000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1))
                .andExpect(jsonPath("$.updatedAt").isString())
                .andExpect(jsonPath("$.days[0].ymd").value("20260914"))
                .andExpect(jsonPath("$.spots[0].lat").value(35.8151))
                .andExpect(jsonPath("$.spots[0].lng").value(127.1530))
                .andExpect(jsonPath("$.spots[0].district").value("전북 전주시"))
                .andExpect(jsonPath("$.spots[0].congestionLevel").value("busy"))
                .andExpect(jsonPath("$.spots[0].intensity").value(org.hamcrest.Matchers.closeTo(0.724, 0.000001)))
                .andExpect(jsonPath("$.spots[0].series[0]").value(72.4))
                .andExpect(jsonPath("$.schemaVersion").doesNotExist());
    }

    @ParameterizedTest
    @CsvSource({
            "lat,91,127.1530,7,15000",
            "lng,35.8151,-181,7,15000",
            "level,35.8151,127.1530,15,15000",
            "radius,35.8151,127.1530,7,0"
    })
    void mapHeatRejectsInvalidViewportParameters(
            String field, String lat, String lng, String level, String radius) throws Exception {
        mockMvc.perform(get("/api/map/heat")
                        .param("lat", lat)
                        .param("lng", lng)
                        .param("level", level)
                        .param("radius", radius))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value(field));
    }

    @Test
    void invalidDateReturnsValidationEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/insights/heatmap")
                        .param("date", "not-a-date")
                        .header("X-Request-Id", "req-insights-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value(org.hamcrest.Matchers.not("req-insights-date")))
                .andExpect(jsonPath("$.details.field").value("date"));
    }
}
