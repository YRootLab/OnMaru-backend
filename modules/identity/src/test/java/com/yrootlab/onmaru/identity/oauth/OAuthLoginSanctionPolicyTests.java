package com.yrootlab.onmaru.identity.oauth;

import com.yrootlab.onmaru.identity.lifecycle.MemberAccessDeniedException;
import com.yrootlab.onmaru.identity.lifecycle.MemberAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void deletedMemberWithActiveSanctionCannotRejoinAsFreshMember() {
        var store = new InMemoryIdentityStore();
        var hasher = new TokenHasher("test-secret");
        var identity = new ExternalIdentity("KAKAO", KAKAO.issuer(), "sanctioned-subject");
        var firstService = new OAuthLoginService(store, hasher, Clock.fixed(NOW, ZoneOffset.UTC));
        var firstState = firstService.startLogin(new StartOAuthLoginCommand(
                KAKAO, "nonce-1", "verifier-1", null, "/discover"));
        var first = firstService.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO, firstState.state(), "nonce-1", "verifier-1", identity));
        assertThat(store.requestDeletion(hasher.hash(first.sessionToken()), NOW)).isPresent();

        var sanctionedService = new OAuthLoginService(store, hasher, Clock.fixed(NOW, ZoneOffset.UTC),
                (memberId, now) -> !memberId.equals(first.memberId()));
        var secondState = sanctionedService.startLogin(new StartOAuthLoginCommand(
                KAKAO, "nonce-2", "verifier-2", null, "/discover"));
        assertThatThrownBy(() -> sanctionedService.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO, secondState.state(), "nonce-2", "verifier-2", identity)))
                .isInstanceOf(MemberAccessDeniedException.class);
        assertThat(store.memberCount()).isEqualTo(1);
        assertThat(store.externalAccountCount()).isEqualTo(1);
    }
}
