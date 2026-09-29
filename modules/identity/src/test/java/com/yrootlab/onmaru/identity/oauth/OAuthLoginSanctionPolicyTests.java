package com.yrootlab.onmaru.identity.oauth;

import com.yrootlab.onmaru.identity.lifecycle.MemberAccessDeniedException;
import com.yrootlab.onmaru.identity.lifecycle.MemberAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthLoginSanctionPolicyTests {

    private static final OAuthProvider KAKAO = new OAuthProvider("KAKAO", "https://kauth.kakao.com");

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");

    @Test
    void doesNotIssueSessionWhenMemberAccessPolicyRejectsMember() {
        var store = new InMemoryIdentityStore();
        var service = new OAuthLoginService(
                store,
                new TokenHasher("test-secret"),
                Clock.fixed(NOW, ZoneOffset.UTC),
                MemberAccessPolicy.rejectAll());
        var identity = new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "subject-1");
        var start = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "nonce", "verifier", null, "/discover"));

        assertThatThrownBy(() -> service.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO, start.state(), "nonce", "verifier", identity)))
                .isInstanceOf(MemberAccessDeniedException.class);
    }
}
