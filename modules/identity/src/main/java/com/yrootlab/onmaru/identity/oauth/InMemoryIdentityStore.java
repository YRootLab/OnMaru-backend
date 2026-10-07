package com.yrootlab.onmaru.identity.oauth;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStatus;
import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleStore;
import com.yrootlab.onmaru.identity.lifecycle.MemberSummary;
import com.yrootlab.onmaru.identity.profile.MemberProfile;
import com.yrootlab.onmaru.identity.profile.MemberProfileBackground;
import com.yrootlab.onmaru.identity.profile.MemberProfileCharacter;
import com.yrootlab.onmaru.identity.profile.MemberProfileGenerator;
import com.yrootlab.onmaru.identity.profile.MemberProfileStore;
import com.yrootlab.onmaru.identity.profile.NewMemberProfile;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryIdentityStore implements IdentityStore, MemberLifecycleStore, MemberProfileStore {

    private static final int GENERATED_NICKNAME_ATTEMPTS = 8;

    private final Map<String, OAuthStateRecord> states = new HashMap<>();
    private final Map<ExternalIdentity, UUID> externalAccounts = new HashMap<>();
    private final Map<UUID, MemberRecord> members = new HashMap<>();
    private final Map<UUID, MemberProfile> profiles = new HashMap<>();
    private final Map<String, StoredSession> sessions = new HashMap<>();
    private final Map<UUID, DeletionRecord> deletionLedger = new HashMap<>();

    @Override
    public synchronized void saveOAuthState(OAuthStateRecord state) {
        states.put(state.stateHash(), state);
    }

    @Override
    public synchronized Optional<OAuthStateRecord> consumeOAuthState(
            String stateHash,
            String provider,
            String browserNonceHash,
            String pkceVerifierHash,
            Instant now) {
        var state = states.get(stateHash);
        if (state == null
                || state.consumedAt() != null
                || !state.provider().equals(provider)
                || !state.browserNonceHash().equals(browserNonceHash)
                || !state.pkceVerifierHash().equals(pkceVerifierHash)
                || !state.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        var consumed = state.consume(now);
        states.put(stateHash, consumed);
        return Optional.of(consumed);
    }

    @Override
    public synchronized Optional<UUID> findLinkedMemberId(ExternalIdentity identity) {
        return Optional.ofNullable(externalAccounts.get(identity));
    }

    @Override
    public synchronized UUID linkExternalIdentity(
            ExternalIdentity identity,
            Supplier<NewMemberProfile> profileSupplier,
            Instant now) {
        var linkedMemberId = externalAccounts.get(identity);
        if (linkedMemberId != null && members.get(linkedMemberId).status() != MemberLifecycleStatus.ACTIVE) {
            externalAccounts.remove(identity);
        }
        return externalAccounts.computeIfAbsent(identity, ignored -> {
            var memberId = UUID.randomUUID();
            NewMemberProfile profile = null;
            for (int attempt = 0; attempt < GENERATED_NICKNAME_ATTEMPTS; attempt++) {
                profile = profileSupplier.get();
                if (!displayNameExists(profile.displayName())) {
                    createMember(memberId, now, profile, profile.displayName());
                    return memberId;
                }
            }
            createMember(memberId, now, profile, MemberAnonymousId.from(memberId));
            return memberId;
        });
    }

    @Override
    public synchronized void saveSession(SessionRecord session) {
        sessions.put(session.tokenHash(), new StoredSession(session, null));
    }

    @Override
    public synchronized Optional<MemberSummary> findActiveMemberBySessionHash(String sessionTokenHash, Instant now) {
        var storedSession = sessions.get(sessionTokenHash);
        if (storedSession == null
                || storedSession.revokedAt() != null
                || !storedSession.session().absoluteExpiresAt().isAfter(now)) {
            return Optional.empty();
        }
        var member = members.get(storedSession.session().memberId());
        if (member == null || member.status() != MemberLifecycleStatus.ACTIVE) {
            return Optional.empty();
        }
        var profile = profiles.get(member.id());
        if (profile == null) {
            throw new IllegalStateException("Active member profile is missing");
        }
        return Optional.of(new MemberSummary(
                member.id(),
                profile.displayName(),
                profile.characterId().name(),
                profile.backgroundId().name()));
    }

    @Override
    public synchronized boolean revokeSession(String sessionTokenHash, Instant now) {
        var storedSession = sessions.get(sessionTokenHash);
        if (storedSession == null || storedSession.revokedAt() != null) {
            return false;
        }
        sessions.put(sessionTokenHash, new StoredSession(storedSession.session(), now));
        return true;
    }

    @Override
    public synchronized int revokeAllSessions(UUID memberId, Instant now) {
        int changed = 0;
        for (var entry : sessions.entrySet()) {
            var session = entry.getValue();
            if (session.session().memberId().equals(memberId) && session.revokedAt() == null) {
                sessions.put(entry.getKey(), new StoredSession(session.session(), now));
                changed++;
            }
        }
        return changed;
    }

    @Override
    public synchronized Optional<MemberLifecycleStatus> requestDeletion(String sessionTokenHash, Instant now) {
        var summary = findActiveMemberBySessionHash(sessionTokenHash, now);
        if (summary.isEmpty()) {
            return Optional.empty();
        }
        var memberId = summary.get().id();
        members.put(memberId, new MemberRecord(memberId, MemberLifecycleStatus.DELETING, members.get(memberId).createdAt()));
        deletionLedger.put(memberId, new DeletionRecord(memberId, now, MemberLifecycleStatus.DELETING));
        revokeSession(sessionTokenHash, now);
        return Optional.of(MemberLifecycleStatus.DELETING);
    }

    @Override
    public synchronized boolean allowsLateWrite(UUID memberId) {
        var member = members.get(memberId);
        return member != null
                && member.status() == MemberLifecycleStatus.ACTIVE
                && !deletionLedger.containsKey(memberId);
    }

    public synchronized UUID createMember(Instant now) {
        return createMember(now, new MemberProfileGenerator().generate());
    }

    public synchronized UUID createMember(Instant now, NewMemberProfile profile) {
        var memberId = UUID.randomUUID();
        var displayName = displayNameExists(profile.displayName())
                ? MemberAnonymousId.from(memberId)
                : profile.displayName();
        createMember(memberId, now, profile, displayName);
        return memberId;
    }

    private void createMember(UUID memberId, Instant now, NewMemberProfile profile, String displayName) {
        members.put(memberId, new MemberRecord(memberId, MemberLifecycleStatus.ACTIVE, now));
        profiles.put(memberId, new MemberProfile(
                memberId,
                displayName,
                profile.characterId(),
                profile.backgroundId(),
                now,
                now));
    }

    private boolean displayNameExists(String displayName) {
        return profiles.values().stream().anyMatch(profile -> profile.displayName().equals(displayName));
    }

    @Override
    public synchronized Optional<MemberProfile> findByMemberId(UUID memberId) {
        return Optional.ofNullable(profiles.get(memberId));
    }

    @Override
    public synchronized Map<UUID, MemberProfile> findByMemberIds(Set<UUID> memberIds) {
        var found = new HashMap<UUID, MemberProfile>();
        for (var memberId : memberIds) {
            var profile = profiles.get(memberId);
            if (profile != null) {
                found.put(memberId, profile);
            }
        }
        return Map.copyOf(found);
    }

    @Override
    public synchronized boolean existsByDisplayNameExcludingMember(String displayName, UUID excludedMemberId) {
        return profiles.values().stream().anyMatch(profile ->
                !profile.memberId().equals(excludedMemberId) && profile.displayName().equals(displayName));
    }

    @Override
    public synchronized Optional<MemberProfile> updateActiveProfile(
            UUID memberId,
            String displayName,
            MemberProfileCharacter characterId,
            MemberProfileBackground backgroundId,
            Instant updatedAt) {
        var member = members.get(memberId);
        var current = profiles.get(memberId);
        if (member == null || member.status() != MemberLifecycleStatus.ACTIVE || current == null) {
            return Optional.empty();
        }
        if (displayName != null && existsByDisplayNameExcludingMember(displayName, memberId)) {
            throw new com.yrootlab.onmaru.identity.profile.MemberProfileDuplicateException();
        }
        var updated = new MemberProfile(
                memberId,
                displayName == null ? current.displayName() : displayName,
                characterId == null ? current.characterId() : characterId,
                backgroundId == null ? current.backgroundId() : backgroundId,
                current.createdAt(),
                updatedAt);
        profiles.put(memberId, updated);
        return Optional.of(updated);
    }

    public synchronized void clear() {
        states.clear();
        externalAccounts.clear();
        members.clear();
        profiles.clear();
        sessions.clear();
        deletionLedger.clear();
    }

    int memberCount() {
        return members.size();
    }

    int externalAccountCount() {
        return externalAccounts.size();
    }

    int profileCount() {
        return profiles.size();
    }

    int sessionCount() {
        return sessions.size();
    }

    private record MemberRecord(UUID id, MemberLifecycleStatus status, Instant createdAt) {
    }

    private record StoredSession(SessionRecord session, Instant revokedAt) {
    }

    private record DeletionRecord(UUID memberId, Instant requestedAt, MemberLifecycleStatus status) {
    }
}
