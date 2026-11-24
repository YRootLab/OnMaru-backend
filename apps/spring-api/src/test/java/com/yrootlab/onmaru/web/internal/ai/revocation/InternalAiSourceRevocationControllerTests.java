package com.yrootlab.onmaru.web.internal.ai.revocation;

import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.internal.ai.revocation.InternalAiSourceRevocationController;
import com.yrootlab.onmaru.persistence.catalog.JdbcAiSourceRevocationStore;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import javax.sql.DataSource;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.mock;

class InternalAiSourceRevocationControllerTests {

    @Test
    void rejectsMissingAndInvalidServiceBearerBeforeReadingFeed() throws Exception {
        SecretProvider secrets = name -> new SecretBundle(name, "service-secret", Optional.empty());
        var controller = new InternalAiSourceRevocationController(
                new JdbcAiSourceRevocationStore(mock(DataSource.class)), secrets);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();

        mvc.perform(get("/internal/v1/ai/source-revocations/watermark"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/v1/ai/source-revocations/watermark")
                        .header("Authorization", "Bearer wrong"))
                .andExpect(status().isUnauthorized());
    }
}
