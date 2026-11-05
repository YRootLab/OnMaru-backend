package com.yrootlab.onmaru.web.internal.mcp.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.catalog.application.query.journey.ApprovedPlaceLookupItem;
import com.yrootlab.onmaru.catalog.application.query.journey.PlaceQueryPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JourneyQueryToolControllerTests {

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private PlaceQueryPort placeQueryPort;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        placeQueryPort = (regionCode, category, limit) -> List.of(
                new ApprovedPlaceLookupItem(
                        "place-1",
                        "전주 경기전",
                        "TOURIST_SPOT",
                        "전북 전주시 완산구 태조로 44",
                        "jeonju",
                        35.8152,
                        127.1498
                )
        );
        var controller = new JourneyQueryToolController(placeQueryPort);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void executesApprovedLookupWithValidGrant() throws Exception {
        var grantId = "grant-12345678901234567890123456789012";
        var request = new JourneyQueryToolRequest(grantId, "jeonju", "TOURIST_SPOT", 5);

        mockMvc.perform(post("/internal/mcp/journey/tools/journey_execute_approved_lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogVersion").value("journey.query.tools.v1"))
                .andExpect(jsonPath("$.places").isArray())
                .andExpect(jsonPath("$.places[0].placeId").value("place-1"))
                .andExpect(jsonPath("$.places[0].name").value("전주 경기전"));
    }

    @Test
    void rejectsInvalidOrShortGrant() throws Exception {
        var request = new JourneyQueryToolRequest("short", "jeonju", "TOURIST_SPOT", 5);

        mockMvc.perform(post("/internal/mcp/journey/tools/journey_execute_approved_lookup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
