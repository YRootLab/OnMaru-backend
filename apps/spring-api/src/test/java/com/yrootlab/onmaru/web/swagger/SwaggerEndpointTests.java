package com.yrootlab.onmaru.web.swagger;

import com.yrootlab.onmaru.OnMaruApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class SwaggerEndpointTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Swagger OpenAPI JSON 문서 엔드포인트(/v3/api-docs)가 200 OK와 올바른 메타데이터를 반환한다")
    void openApiDocsEndpointReturnsValidSpec() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("OnMaru Backend REST API"))
                .andExpect(jsonPath("$.info.version").value("v1.0.0"));
    }

    @Test
    @DisplayName("Swagger UI HTML 리다이렉트 및 인덱스 페이지가 정상 제공된다")
    void swaggerUiHtmlAccessible() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }
}
