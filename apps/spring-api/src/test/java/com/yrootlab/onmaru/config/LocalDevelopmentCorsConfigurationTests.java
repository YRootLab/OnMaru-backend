package com.yrootlab.onmaru.config;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = OnMaruApplication.class,
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class LocalDevelopmentCorsConfigurationTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void allowsCorsPreflightFromEverySupportedLocalDevelopmentPort() throws Exception {
        for (var port = 3000; port <= 3007; port++) {
            var origin = "http://localhost:" + port;

            mockMvc.perform(options("/api/v1/home/curated-courses")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "GET"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
        }
    }

    @Test
    void allowsCorsPreflightFromRenderFrontendForOdiiStories() throws Exception {
        var origin = "https://onmaru-web.onrender.com";

        mockMvc.perform(options("/api/v1/odii/stories")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
    }

    @Test
    void rejectsCorsPreflightFromOriginsOutsideTheLocalDevelopmentAllowlist() throws Exception {
        mockMvc.perform(options("/api/v1/home/curated-courses")
                        .header("Origin", "http://localhost:3008")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
