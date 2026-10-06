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
        var displayName = patch.displayName() == null
                ? null
                : normalizeDisplayName(patch.displayName());
        var characterId = patch.characterId() == null
                ? null
                : parseCharacter(patch.characterId());
        var backgroundId = patch.backgroundId() == null
                ? null
                : parseBackground(patch.backgroundId());
        if (displayName != null && store.existsByDisplayNameExcludingMember(displayName, memberId)) {
            throw new MemberProfileDuplicateException();
        }
        return store.updateActiveProfile(memberId, displayName, characterId, backgroundId, updatedAt);
    }

    public Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
        if (memberIds == null || memberIds.isEmpty()) {
            return Map.of();
        }
        return store.findByMemberIds(Set.copyOf(memberIds));
    }

    public boolean isDisplayNameAvailable(UUID memberId, String rawDisplayName) {
        var displayName = normalizeDisplayName(rawDisplayName);
        return !store.existsByDisplayNameExcludingMember(displayName, memberId);
    }

    private String normalizeDisplayName(String raw) {
        if (raw == null) {
            throw new MemberProfileInvalidException("displayName");
        }
        var normalized = Normalizer.normalize(raw.strip(), Normalizer.Form.NFC);
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 2
                || length > 20
                || normalized.codePoints().anyMatch(codePoint -> Character.isISOControl(codePoint)
                || Character.getType(codePoint) == Character.LINE_SEPARATOR
                || Character.getType(codePoint) == Character.PARAGRAPH_SEPARATOR)) {
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
