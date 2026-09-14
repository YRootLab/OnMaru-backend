package com.yrootlab.onmaru.security.oauth.kakao;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.ExternalIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, KakaoOAuthWebBoundaryTests.TestKakaoConfig.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class KakaoOAuthWebBoundaryTests {

    private static final Pattern STATE_COOKIE =
            Pattern.compile("(__Host-onmaru-oauth-nonce)=([^;]+);.*Path=/.*Secure.*HttpOnly.*SameSite=Lax");
    private static final Pattern SESSION_COOKIE =
            Pattern.compile("(__Host-onmaru-session)=([^;]+);.*Path=/.*Secure.*HttpOnly.*SameSite=Lax");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void loginRedirectIssuesOpaqueStateAndNonceCookie() throws Exception {
        var result = mockMvc.perform(get("/auth/kakao/login")
                        .queryParam("returnTo", "/discover")
                        .queryParam("explorationId", "550e8400-e29b-41d4-a716-446655440000"))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", startsWith("https://kauth.kakao.com/oauth/authorize?")))
                .andExpect(header().string("Location", containsString("client_id=test-client")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string("Location", containsString("state=")))
                .andReturn();

        assertThat(STATE_COOKIE.matcher(result.getResponse().getHeader("Set-Cookie")).matches()).isTrue();
    }

    @Test
    void callbackRotatesToOpaqueSessionCookieAndDoesNotExposeProviderToken() throws Exception {
        var login = mockMvc.perform(get("/auth/kakao/login"))
                .andReturn();
        var location = login.getResponse().getHeader("Location");
        var state = location.substring(location.indexOf("state=") + "state=".length()).split("&")[0];
        var nonceCookie = login.getResponse().getCookie("__Host-onmaru-oauth-nonce");
        var verifierCookie = login.getResponse().getCookie("__Host-onmaru-oauth-verifier");

        var callback = mockMvc.perform(get("/auth/kakao/callback")
                        .queryParam("code", "fixture-code")
                        .queryParam("state", state)
                        .cookie(nonceCookie, verifierCookie))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Location", "/discover?auth=success"))
                .andReturn();

        var setCookie = callback.getResponse().getHeader("Set-Cookie");
        assertThat(SESSION_COOKIE.matcher(setCookie).matches()).isTrue();
        assertThat(callback.getResponse().getContentAsString()).doesNotContain("fixture-access-token");
    }

    @Test
    void callbackFailureDoesNotClearGuestCookieOrCreateSession() throws Exception {
        mockMvc.perform(get("/auth/kakao/callback")
                        .queryParam("error", "access_denied")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-guest", "guest-token")))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", "/discover?auth=failed"));
    }

    @TestConfiguration
    static class TestKakaoConfig {

        @Bean
        @Primary
        KakaoOAuthProperties testKakaoOAuthProperties() {
            var properties = new KakaoOAuthProperties();
            properties.setClientId("test-client");
            properties.setRedirectUri("https://api.onmaru.test/auth/kakao/callback");
            return properties;
        }

        @Bean
        @Primary
        KakaoOAuthClient testKakaoOAuthClient() {
            return (code, verifier) -> new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "subject-" + code);
        }
    }
}
