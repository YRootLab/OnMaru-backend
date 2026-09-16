package com.yrootlab.onmaru.identity.guest;

import java.time.Instant;
import java.util.UUID;

public record IssuedGuestCredential(UUID guestId, String rawToken, Instant expiresAt) {
}
