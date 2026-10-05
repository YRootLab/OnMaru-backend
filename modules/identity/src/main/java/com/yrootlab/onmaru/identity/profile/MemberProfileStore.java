package com.yrootlab.onmaru.identity.profile;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface MemberProfileStore {

    Optional<MemberProfile> findByMemberId(UUID memberId);

    Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds);

    Optional<MemberProfile> updateActiveProfile(
            UUID memberId,
            String displayName,
            MemberProfileCharacter characterId,
            MemberProfileBackground backgroundId,
            Instant updatedAt);
}
