package com.yrootlab.onmaru.identity.oauth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

public final class OAuthLoginService {

    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    private static final Duration SESSION_TTL = Duration.ofDays(7);

    private final IdentityStore store;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthLoginService(IdentityStore store, TokenHasher tokenHasher, Clock clock) {
        this.store = store;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    public OAuthLoginStart startLogin(StartOAuthLoginCommand command) {
        var now = clock.instant();
        var rawState = randomToken();
        var returnPath = normalizeReturnPath(command.returnPath());
        store.saveOAuthState(new OAuthStateRecord(
                tokenHasher.hash(rawState),
                null,
                tokenHasher.hash(command.browserNonce()),
                tokenHasher.hash(command.pkceVerifier()),
                command.explorationId(),
                command.provider().name(),
                returnPath,
                now.plus(STATE_TTL),
                null));
        return new OAuthLoginStart(rawState, returnPath);
    }

    public OAuthLoginResult completeLogin(CompleteOAuthLoginCommand command) {
        var now = clock.instant();
        if (!command.expectedProvider().matches(command.externalIdentity())) {
            throw new OAuthStateRejectedException("issuer does not match expected provider");
        }
        var state = store.consumeOAuthState(
                        tokenHasher.hash(command.state()),
                        command.expectedProvider().name(),
                        tokenHasher.hash(command.browserNonce()),
                        tokenHasher.hash(command.pkceVerifier()),
                        now)
                .orElseThrow(() -> new OAuthStateRejectedException("invalid oauth state"));
        var memberId = store.linkExternalIdentity(command.externalIdentity(), now);
        var sessionToken = randomToken();
        var expiresAt = now.plus(SESSION_TTL);
        store.saveSession(new SessionRecord(tokenHasher.hash(sessionToken), memberId, now, now, expiresAt));
        return new OAuthLoginResult(memberId, sessionToken, state.returnPath(), expiresAt);
    }

    private String randomToken() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeReturnPath(String returnPath) {
        if (returnPath == null || returnPath.isBlank() || !returnPath.startsWith("/") || returnPath.startsWith("//")) {
            return "/discover";
        }
        if (returnPath.contains("\\") || returnPath.contains("\n") || returnPath.contains("\r")) {
            return "/discover";
        }
        return returnPath;
    }
}
