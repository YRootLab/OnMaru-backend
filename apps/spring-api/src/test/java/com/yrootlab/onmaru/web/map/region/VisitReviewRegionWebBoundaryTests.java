package com.yrootlab.onmaru.web.map.region;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.community.query.InMemoryVisitReviewStore;
import com.yrootlab.onmaru.community.query.VisitReviewProjection;
import com.yrootlab.onmaru.community.query.VisitReviewStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class VisitReviewRegionWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryVisitReviewStore visitReviewStore;

    @BeforeEach
    void setUp() {
        visitReviewStore.clear();
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000001",
                "kr-45-jeonju", VisitReviewStatus.PUBLISHED));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000002",
                "kr-45-jeonju", VisitReviewStatus.PUBLISHED));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000003",
                "kr-11-jongno", VisitReviewStatus.PUBLISHED));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000004",
                "kr-99-unknown", VisitReviewStatus.PUBLISHED));
        visitReviewStore.add(review("00000000-0000-0000-0000-000000000005",
                "kr-45-jeonju", VisitReviewStatus.HIDDEN));
    }

    @Test
    void listRootAndChildRegionReviewCounts() throws Exception {
        mockMvc.perform(get("/api/v1/visit-review-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.regionRevision").value("region-rev-2026-09-15"))
                .andExpect(jsonPath("$.parentRegionCode", nullValue()))
                .andExpect(jsonPath("$.unassignedCount").value(1))
                .andExpect(jsonPath("$.items[0].region.regionCode").value("kr-11"))
                .andExpect(jsonPath("$.items[0].reviewCount").value(1))
                .andExpect(jsonPath("$.items[1].region.regionCode").value("kr-45"))
                .andExpect(jsonPath("$.items[1].reviewCount").value(2));

        mockMvc.perform(get("/api/v1/visit-review-regions")
                        .param("parentRegionCode", "kr-45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parentRegionCode").value("kr-45"))
                .andExpect(jsonPath("$.unassignedCount").value(0))
                .andExpect(jsonPath("$.items[0].region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.items[0].region.parentRegionCode").value("kr-45"))
                .andExpect(jsonPath("$.items[0].reviewCount").value(2));
    }

    @Test
    void resolveRegionUsesNoStoreAndRejectsInvalidInputs() throws Exception {
        mockMvc.perform(get("/api/v1/regions/resolve")
                        .param("lat", "35.8151")
                        .param("lng", "127.1530"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.regionRevision").value("region-rev-2026-09-15"))
                .andExpect(jsonPath("$.coordinates.lat").value(35.8151))
                .andExpect(jsonPath("$.coordinates.lng").value(127.1530))
                .andExpect(jsonPath("$.candidates[0].region.regionCode").value("kr-45"))
                .andExpect(jsonPath("$.candidates[1].region.regionCode").value("kr-45-jeonju"))
                .andExpect(jsonPath("$.candidates[1].confidence").value(0.99));

        mockMvc.perform(get("/api/v1/regions/resolve")
                        .param("lat", "91.0")
                        .param("lng", "127.1530")
                        .header("X-Request-Id", "req-region-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value("req-region-invalid"))
                .andExpect(jsonPath("$.details.field").value("lat"));
    }

    @Test
    void leafParentIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/visit-review-regions")
                        .param("parentRegionCode", "kr-45-jeonju")
                        .header("X-Request-Id", "req-leaf-parent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value("parentRegionCode"));
    }

    private VisitReviewProjection review(String id, String regionCode, VisitReviewStatus status) {
        return new VisitReviewProjection(
                UUID.fromString(id),
                "p-test",
                "테스트 장소",
                regionCode,
                35.8151,
                127.1530,
                "좋았습니다.",
                Instant.parse("2026-09-15T03:00:00Z"),
                UUID.fromString("4de5c657-a606-4f37-93d4-0be9b7544712"),
                Set.of(),
                status);
    }
}
