package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.admin.auth.AdminJwtTokenCodec;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class AdminApiWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminJwtTokenCodec tokenCodec;

    @Test
    void allAdminRoutesRejectMissingBearerToken() throws Exception {
        String memberId = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
        String sanctionId = "cccccccc-cccc-cccc-cccc-cccccccccccc";
        String placeId = "dddddddd-dddd-dddd-dddd-dddddddddddd";
        String[] paths = {
                "/api/v1/admin/dashboard/summary",
                "/api/v1/admin/users",
                "/api/v1/admin/users/" + memberId + "/sanctions",
                "/api/v1/admin/curations",
                "/api/v1/admin/pipelines/kto-korean-tour/status"
        };
        for (String path : paths) {
            mockMvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
        }
        mockMvc.perform(post("/api/v1/admin/pipelines/kto-korean-tour/runs").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/admin/curations/" + placeId).with(csrf())
                        .contentType("application/json")
                        .content("{\"category\":\"VILLAGE\",\"included\":true,\"badges\":[]}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(delete("/api/v1/admin/users/" + memberId + "/sanctions/" + sanctionId).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listRoutesReturnValidationErrorForDamagedCursor() throws Exception {
        String bearer = "Bearer " + tokenCodec.issue(new AdminPrincipal(
                UUID.fromString("00000000-0000-0000-0000-000000000509"),
                "admin@example.com", AdminRole.ADMIN));
        for (String path : new String[] {
                "/api/v1/admin/reviews", "/api/v1/admin/reports",
                "/api/v1/admin/users", "/api/v1/admin/curations",
                "/api/v1/admin/moderation/queue"}) {
            mockMvc.perform(get(path).param("cursor", "damaged").header("Authorization", bearer))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor csrf() {
        return request -> {
            String token = "test-csrf-token";
            request.setCookies(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", token));
            request.addHeader("X-CSRF-TOKEN", token);
            return request;
        };
    }
}
