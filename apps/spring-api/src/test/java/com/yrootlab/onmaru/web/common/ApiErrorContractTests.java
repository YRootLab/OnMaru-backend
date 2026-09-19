package com.yrootlab.onmaru.web.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, ApiErrorContractTests.ContractTestConfig.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class ApiErrorContractTests {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void validationErrorsUseSchemaVersion12EnvelopeAndRequestId() throws Exception {
        var result = mockMvc.perform(post("/contract/probes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}")
                        .header("X-Request-Id", "req-contract-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.requestId").value(not("req-contract-1")))
                .andExpect(jsonPath("$.details.fieldErrors.name").value("must not be blank"))
                .andReturn();

        var responseBody = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(result.getResponse().getHeader("X-Request-Id"))
                .isEqualTo(responseBody.path("requestId").textValue());
    }

    @Test
    void cursorExceptionsMapToDocumentedErrorCodes() throws Exception {
        mockMvc.perform(get("/contract/cursor-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"))
                .andExpect(jsonPath("$.requestId", not(emptyOrNullString())));

        mockMvc.perform(get("/contract/cursor-expired"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("CURSOR_EXPIRED"));
    }

    @Test
    void idempotencyConflictsUseDocumented409Envelope() throws Exception {
        mockMvc.perform(post("/contract/idempotency-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void malformedIdempotencyKeysUseValidationEnvelope() throws Exception {
        mockMvc.perform(post("/contract/idempotency-key-invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.fieldErrors.Idempotency-Key").value("must be a UUID"));
    }

    @TestConfiguration
    static class ContractTestConfig {

        @Bean
        ContractProbeController contractProbeController() {
            return new ContractProbeController();
        }
    }

    @RestController
    static class ContractProbeController {

        @PostMapping("/contract/probes")
        void validate(@Valid @RequestBody ProbeRequest request) {
        }

        @PostMapping("/contract/idempotency-conflict")
        void conflict() {
            throw new com.yrootlab.onmaru.web.common.idempotency.IdempotencyConflictException();
        }

        @PostMapping("/contract/idempotency-key-invalid")
        void invalidIdempotencyKey() {
            throw new com.yrootlab.onmaru.web.common.idempotency.IdempotencyKeyInvalidException();
        }

        @org.springframework.web.bind.annotation.GetMapping("/contract/cursor-invalid")
        void invalidCursor() {
            throw new com.yrootlab.onmaru.web.common.cursor.CursorInvalidException();
        }

        @org.springframework.web.bind.annotation.GetMapping("/contract/cursor-expired")
        void expiredCursor() {
            throw new com.yrootlab.onmaru.web.common.cursor.CursorExpiredException();
        }
    }

    record ProbeRequest(@NotBlank String name) {
    }
}
