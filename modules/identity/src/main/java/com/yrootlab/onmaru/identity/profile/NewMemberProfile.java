package com.yrootlab.onmaru.identity.profile;

import java.util.Objects;

public record NewMemberProfile(
        String displayName,
        MemberProfileCharacter characterId,
        MemberProfileBackground backgroundId) {

    public NewMemberProfile {
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(characterId, "characterId");
        Objects.requireNonNull(backgroundId, "backgroundId");
    }
}
