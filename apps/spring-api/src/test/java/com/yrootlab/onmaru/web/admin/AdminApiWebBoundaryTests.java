package com.yrootlab.onmaru.web.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.admin.auth.AdminJwtTokenCodec;
import com.yrootlab.onmaru.admin.auth.AdminAccount;
import com.yrootlab.onmaru.admin.auth.AdminAccountStatus;
import com.yrootlab.onmaru.admin.auth.AdminPasswordHasher;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import com.yrootlab.onmaru.admin.auth.InMemoryAdminAccountStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

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

    private static final String REFRESH_COOKIE = "__Host-onmaru-admin-refresh";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AdminJwtTokenCodec tokenCodec;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private InMemoryAdminAccountStore adminAccounts;

    @BeforeEach
    void provisionAdminAccount() {
        adminAccounts.replace(new AdminAccount(
                UUID.fromString("00000000-0000-0000-0000-000000000597"),
                "admin-cookie-test@onmaru.kr",
                "cookie test admin",
                AdminRole.ADMIN,
                new AdminPasswordHasher().encode("correct horse battery staple"),
                AdminAccountStatus.ACTIVE));
    }

    @Test
    void adminLoginAndRefreshIssueHostCookiesAtRootPathWithoutDomain() throws Exception {
        CsrfCredential csrf = csrfCredential();
        MvcResult login = adminLogin(csrf);

        String loginSetCookie = login.getResponse().getHeader("Set-Cookie");
        assertRefreshCookie(loginSetCookie, false);

        MvcResult refresh = mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .cookie(refreshCookie(loginSetCookie), csrf.cookie())
                        .header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andReturn();

        String refreshSetCookie = refresh.getResponse().getHeader("Set-Cookie");
        assertRefreshCookie(refreshSetCookie, false);
        String accessToken = objectMapper.readTree(refresh.getResponse().getContentAsString()).get("accessToken").asText();
        mockMvc.perform(get("/api/v1/auth/admin/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminLogoutDeletesRootPathCookieAndRevokesRefreshSession() throws Exception {
        CsrfCredential csrf = csrfCredential();
        MvcResult login = adminLogin(csrf);
        String loginSetCookie = login.getResponse().getHeader("Set-Cookie");
        jakarta.servlet.http.Cookie refreshCookie = refreshCookie(loginSetCookie);
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString()).get("accessToken").asText();

        MvcResult logout = mockMvc.perform(post("/api/v1/auth/admin/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(refreshCookie, csrf.cookie())
                        .header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isNoContent())
                .andReturn();

        assertRefreshCookie(logout.getResponse().getHeader("Set-Cookie"), true);
        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .cookie(refreshCookie, csrf.cookie())
                        .header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminUnsafeAuthRequestsStillRequireMatchingCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType("application/json")
                        .content("{\"email\":\"admin-cookie-test@onmaru.kr\",\"password\":\"correct horse battery staple\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/auth/admin/refresh")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "cookie-token"))
                        .header("X-CSRF-TOKEN", "wrong-token"))
                .andExpect(status().isForbidden());
    }

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

    private CsrfCredential csrfCredential() throws Exception {
        MvcResult result = mockMvc.perform(get("/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new CsrfCredential(
                body.get("token").asText(),
                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", body.get("token").asText()));
    }

    private MvcResult adminLogin(CsrfCredential csrf) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/admin/login")
                        .contentType("application/json")
                        .content("{\"email\":\"admin-cookie-test@onmaru.kr\",\"password\":\"correct horse battery staple\"}")
                        .cookie(csrf.cookie())
                        .header("X-CSRF-TOKEN", csrf.token()))
                .andExpect(status().isOk())
                .andReturn();
    }

    private void assertRefreshCookie(String setCookie, boolean deleted) {
        org.assertj.core.api.Assertions.assertThat(setCookie)
                .isNotBlank()
                .startsWith(REFRESH_COOKIE + "=")
                .contains("Path=/")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .doesNotContain("Domain=")
                .doesNotContain("Path=/api/v1/auth/admin");
        if (deleted) {
            org.assertj.core.api.Assertions.assertThat(setCookie).contains("Max-Age=0");
        }
    }

    private jakarta.servlet.http.Cookie refreshCookie(String setCookie) {
        String value = setCookie.substring((REFRESH_COOKIE + "=").length(), setCookie.indexOf(';'));
        return new jakarta.servlet.http.Cookie(REFRESH_COOKIE, value);
    }

    private record CsrfCredential(String token, jakarta.servlet.http.Cookie cookie) {
    }
}
