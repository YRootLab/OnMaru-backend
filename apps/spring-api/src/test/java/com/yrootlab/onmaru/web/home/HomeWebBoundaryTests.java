package com.yrootlab.onmaru.web.home;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class HomeWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exposesCanonicalHomeResourcesAndSafeCompatibilityAliases() throws Exception {
        mockMvc.perform(get("/api/v1/home/curated-courses"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"));
        mockMvc.perform(get("/api/home/curated-courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"));

        mockMvc.perform(get("/api/v1/home/trending-sounds"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
        mockMvc.perform(get("/api/home/trending-sounds"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));

        mockMvc.perform(get("/api/v1/home/popular-regions"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"));
        mockMvc.perform(get("/api/home/popular-regions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"));
    }

    @Test
    void curatedCoursesPreservePublishedRankingAcrossTheExpandedCategories() throws Exception {
        mockMvc.perform(get("/api/v1/home/curated-courses").param("limit", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].category").value("HANOK"))
                .andExpect(jsonPath("$.items[3].category").value("HISTORIC_SITE"))
                .andExpect(jsonPath("$.items[9].category").value("NATURE_SITE"))
                .andExpect(jsonPath("$.items[11].category").value("LEISURE_ACTIVITY"))
                .andExpect(jsonPath("$.hasMore").value(true));
    }

    @Test
    void curatedCoursesReturnMoreThanTwentyPublishedCards() throws Exception {
        mockMvc.perform(get("/api/v1/home/curated-courses").param("limit", "25"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(25))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void curatedCoursesCanFilterEachNewHomeCategory() throws Exception {
        mockMvc.perform(get("/api/v1/home/curated-courses")
                        .param("category", "HISTORIC_SITE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[*].category").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("HISTORIC_SITE"))));
        mockMvc.perform(get("/api/v1/home/curated-courses")
                        .param("category", "NATURE_SITE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[*].category").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("NATURE_SITE"))));
        mockMvc.perform(get("/api/v1/home/curated-courses")
                        .param("category", "LEISURE_ACTIVITY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isNotEmpty())
                .andExpect(jsonPath("$.items[*].category").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("LEISURE_ACTIVITY"))));
    }
}
