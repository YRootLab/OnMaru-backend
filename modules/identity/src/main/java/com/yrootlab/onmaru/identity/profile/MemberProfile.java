package com.yrootlab.onmaru.identity.profile;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MemberProfile(
        UUID memberId,
        String displayName,
        MemberProfileCharacter characterId,
        MemberProfileBackground backgroundId,
        Instant createdAt,
        Instant updatedAt) {

    public MemberProfile {
        Objects.requireNonNull(memberId, "memberId");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(backgroundId, "backgroundId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
