package com.yrootlab.onmaru.identity.oauth;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OAuthLoginServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC);
    private static final OAuthProvider KAKAO = new OAuthProvider("KAKAO", "https://kauth.kakao.com");

    @Test
    void rejectsStateReplayAndTampering() {
        var store = new InMemoryIdentityStore();
        var service = new OAuthLoginService(store, new TokenHasher("test-pepper"), CLOCK);
        var started = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "browser-nonce", "pkce-verifier", null, "/discover"));

        var first = service.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO,
                started.state(),
                "browser-nonce",
                "pkce-verifier",
                new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "kakao-1")));

        assertThat(first.memberId()).isNotNull();
        assertThatThrownBy(() -> service.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO,
                started.state(),
                "browser-nonce",
                "pkce-verifier",
                new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "kakao-1"))))
                .isInstanceOf(OAuthStateRejectedException.class)
                .hasMessageContaining("invalid oauth state");

        var other = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "browser-nonce", "pkce-verifier", null, "/discover"));
        assertThatThrownBy(() -> service.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO,
                other.state() + "-tampered",
                "browser-nonce",
                "pkce-verifier",
                new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "kakao-1"))))
                .isInstanceOf(OAuthStateRejectedException.class);
    }

    @Test
    void rejectsIssuerConfusionBeforeCreatingSession() {
        var store = new InMemoryIdentityStore();
        var service = new OAuthLoginService(store, new TokenHasher("test-pepper"), CLOCK);
        var started = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "browser-nonce", "pkce-verifier", null, "/discover"));

        assertThatThrownBy(() -> service.completeLogin(new CompleteOAuthLoginCommand(
                KAKAO,
                started.state(),
                "browser-nonce",
                "pkce-verifier",
                new ExternalIdentity("KAKAO", "https://issuer.example", "kakao-1"))))
                .isInstanceOf(OAuthStateRejectedException.class)
                .hasMessageContaining("issuer");

        assertThat(store.memberCount()).isZero();
        assertThat(store.sessionCount()).isZero();
    }

    @Test
    void concurrentCallbacksForSameExternalIdentityCreateOneMember() throws Exception {
        var store = new InMemoryIdentityStore();
        var service = new OAuthLoginService(store, new TokenHasher("test-pepper"), CLOCK);
        var firstState = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "browser-1", "pkce-1", null, "/discover"));
        var secondState = service.startLogin(new StartOAuthLoginCommand(
                KAKAO, "browser-2", "pkce-2", null, "/discover"));
        var identity = new ExternalIdentity("KAKAO", "https://kauth.kakao.com", "same-subject");

        var results = runRace(
                () -> service.completeLogin(new CompleteOAuthLoginCommand(
                        KAKAO, firstState.state(), "browser-1", "pkce-1", identity)),
                () -> service.completeLogin(new CompleteOAuthLoginCommand(
                        KAKAO, secondState.state(), "browser-2", "pkce-2", identity)));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).memberId()).isEqualTo(results.get(1).memberId());
        assertThat(store.memberCount()).isEqualTo(1);
        assertThat(store.externalAccountCount()).isEqualTo(1);
        assertThat(store.sessionCount()).isEqualTo(2);
    }

    @Test
    void normalizesUnsafeReturnPathToDiscover() {
        var service = new OAuthLoginService(new InMemoryIdentityStore(), new TokenHasher("test-pepper"), CLOCK);

        var started = service.startLogin(new StartOAuthLoginCommand(
                KAKAO,
                "browser-nonce",
                "pkce-verifier",
                UUID.randomUUID(),
                "https://evil.example/callback"));

        assertThat(started.returnPath()).isEqualTo("/discover");
    }

    private static java.util.List<OAuthLoginResult> runRace(
            Callable<OAuthLoginResult> first,
            Callable<OAuthLoginResult> second) throws Exception {
        var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            java.util.List<Callable<OAuthLoginResult>> tasks = java.util.List.of(
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return first.call();
                    },
                    () -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        return second.call();
                    }
            );
            var futures = executor.invokeAll(tasks);
            var results = new java.util.ArrayList<OAuthLoginResult>();
            for (var future : futures) {
                results.add(future.get(5, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(Duration.ofSeconds(5).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }
    }
}
