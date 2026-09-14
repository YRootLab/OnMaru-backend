package com.yrootlab.onmaru.identity.oauth;

import java.time.Instant;
import java.util.UUID;

public record OAuthLoginResult(UUID memberId, String sessionToken, String returnPath, Instant expiresAt) {
}
