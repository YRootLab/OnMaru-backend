package com.yrootlab.onmaru.web.member;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.identity.profile.MemberProfileBackground;
import com.yrootlab.onmaru.identity.profile.MemberProfileCharacter;
import com.yrootlab.onmaru.identity.profile.NewMemberProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = OnMaruApplication.class, properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class MemberLifecycleWebBoundaryTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore store;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");
    private UUID memberId;

    @BeforeEach
    void setUp() {
        store.clear();
        memberId = store.createMember(clock.instant(), new NewMemberProfile(
                "고요한 마루 0552",
                MemberProfileCharacter.CHARACTER_03,
                MemberProfileBackground.BACKGROUND_07));
        store.saveSession(new SessionRecord(
                hasher.hash("member-session"),
                memberId,
                clock.instant(),
                clock.instant(),
                clock.instant().plusSeconds(3600)));
    }

    @Test
    void memberMeRequiresSessionAndReturnsOpaqueMember() throws Exception {
        mockMvc.perform(get("/api/v1/members/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/members/me")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.id", not(emptyOrNullString())))
                .andExpect(jsonPath("$.displayName").value("고요한 마루 0552"))
                .andExpect(jsonPath("$.characterId").value("CHARACTER_03"))
                .andExpect(jsonPath("$.backgroundId").value("BACKGROUND_07"));
    }

    @Test
    void patchMemberProfileUpdatesPresentFieldsAndPreservesOmittedFields() throws Exception {
        mockMvc.perform(profilePatch("""
                        {"displayName":"  한옥 산책자  ","characterId":"CHARACTER_10"}
                        """))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.displayName").value("한옥 산책자"))
                .andExpect(jsonPath("$.characterId").value("CHARACTER_10"))
                .andExpect(jsonPath("$.backgroundId").value("BACKGROUND_07"));

        mockMvc.perform(profilePatch("""
                        {"backgroundId":"BACKGROUND_09"}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("한옥 산책자"))
                .andExpect(jsonPath("$.characterId").value("CHARACTER_10"))
                .andExpect(jsonPath("$.backgroundId").value("BACKGROUND_09"));

        mockMvc.perform(get("/api/v1/members/me")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("한옥 산책자"))
                .andExpect(jsonPath("$.characterId").value("CHARACTER_10"))
                .andExpect(jsonPath("$.backgroundId").value("BACKGROUND_09"));
    }

    @Test
    void patchMemberProfileRequiresCsrfAndActiveSession() throws Exception {
        mockMvc.perform(patch("/api/v1/members/me")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"한옥 산책자\"}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        mockMvc.perform(patch("/api/v1/members/me")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"한옥 산책자\"}")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }

    @Test
    void patchMemberProfileRejectsEmptyNullAndInvalidFields() throws Exception {
        assertProfileValidation("{}", "profile");
        assertProfileValidation("{\"displayName\":null,\"characterId\":\"CHARACTER_02\"}", "displayName");
        assertProfileValidation("{\"displayName\":\"가\"}", "displayName");
        assertProfileValidation("{\"characterId\":\"CHARACTER_00\"}", "characterId");
        assertProfileValidation("{\"characterId\":\"CHARACTER_11\"}", "characterId");
        assertProfileValidation("{\"backgroundId\":\"BACKGROUND_00\"}", "backgroundId");
        assertProfileValidation("{\"backgroundId\":\"BACKGROUND_11\"}", "backgroundId");
    }

    @Test
    void logoutRevokesSessionCookieImmediately() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().maxAge("__Host-onmaru-session", 0));

        mockMvc.perform(get("/api/v1/members/me")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteCurrentMemberMarksDeletingAndRevokesSession() throws Exception {
        mockMvc.perform(delete("/api/v1/members/me")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                        .header("X-CSRF-TOKEN", "same-token"))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(cookie().maxAge("__Host-onmaru-session", 0))
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.status").value("DELETING"));

        mockMvc.perform(get("/api/v1/members/me")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session")))
                .andExpect(status().isUnauthorized());
        org.assertj.core.api.Assertions.assertThat(store.allowsLateWrite(memberId)).isFalse();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder profilePatch(String content) {
        return patch("/api/v1/members/me")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(content)
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", "member-session"),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "same-token"))
                .header("X-CSRF-TOKEN", "same-token");
    }

    private void assertProfileValidation(String content, String field) throws Exception {
        mockMvc.perform(profilePatch(content))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details.field").value(field));
    }
}
