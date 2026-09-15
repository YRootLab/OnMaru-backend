package com.yrootlab.onmaru.web.insights;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
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
