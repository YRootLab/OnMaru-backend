package com.yrootlab.onmaru.identity.profile;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class MemberProfileService {

    private final MemberProfileStore store;

    public MemberProfileService(MemberProfileStore store) {
        this.store = store;
    }

    public Optional<MemberProfile> updateActiveProfile(
            UUID memberId,
            MemberProfilePatch patch,
            Instant updatedAt) {
        if (patch == null
                || patch.displayName() == null && patch.characterId() == null && patch.backgroundId() == null) {
            throw new MemberProfileInvalidException("profile");
        }
        var current = store.findByMemberId(memberId);
        if (current.isEmpty()) {
            return Optional.empty();
        }
        var previous = current.get();
        var displayName = patch.displayName() == null
                ? previous.displayName()
                : normalizeDisplayName(patch.displayName());
        var characterId = patch.characterId() == null
                ? previous.characterId()
                : parseCharacter(patch.characterId());
        var backgroundId = patch.backgroundId() == null
                ? previous.backgroundId()
                : parseBackground(patch.backgroundId());
        return store.updateActiveProfile(memberId, displayName, characterId, backgroundId, updatedAt);
    }

    public Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        return store.findByMemberIds(Set.copyOf(memberIds));
    }

    private String normalizeDisplayName(String raw) {
        var normalized = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2
                || length > 20
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new MemberProfileInvalidException("displayName");
        }
        return normalized;
    }

    private MemberProfileCharacter parseCharacter(String raw) {
        try {
            return MemberProfileCharacter.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw new MemberProfileInvalidException("characterId");
        }
    }

    private MemberProfileBackground parseBackground(String raw) {
        try {
            return MemberProfileBackground.valueOf(raw);
        } catch (IllegalArgumentException exception) {
            throw new MemberProfileInvalidException("backgroundId");
        }
    }
}
