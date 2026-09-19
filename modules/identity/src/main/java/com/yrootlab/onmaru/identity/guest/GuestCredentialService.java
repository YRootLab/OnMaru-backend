package com.yrootlab.onmaru.identity.guest;

import com.yrootlab.onmaru.identity.oauth.TokenHasher;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

public final class GuestCredentialService {

    private static final Duration GUEST_TTL = Duration.ofHours(24);

    private final GuestOwnershipStore store;
    private final TokenHasher tokenHasher;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public GuestCredentialService(GuestOwnershipStore store, TokenHasher tokenHasher, Clock clock) {
        this.store = store;
        this.tokenHasher = tokenHasher;
        this.clock = clock;
    }

    public IssuedGuestCredential issue() {
        var bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        var rawToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var guestId = UUID.randomUUID();
        var expiresAt = clock.instant().plus(GUEST_TTL);
        store.saveGuest(guestId, tokenHasher.hash(rawToken), expiresAt);
        return new IssuedGuestCredential(guestId, rawToken, expiresAt);
    }

    public Optional<UUID> resolve(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return store.findActiveGuest(tokenHasher.hash(rawToken), clock.instant());
    }

    public void linkExploration(UUID guestId, UUID explorationId) {
        store.saveGuestExploration(explorationId, guestId, clock.instant().plus(GUEST_TTL));
    }

    public void clear() {
        store.clearGuests();
    }
}
