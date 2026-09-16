package com.yrootlab.onmaru.web.audio;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class OdiiStoryFailClosedWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void defaultsToUnavailableUntilAProductionQueryStoreIsConnected() throws Exception {
        mockMvc.perform(get("/api/v1/odii/stories")
                        .header("X-Request-Id", "req-odii-default-unavailable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.requestId")
                        .value(org.hamcrest.Matchers.not("req-odii-default-unavailable")));
    }
}
