package com.yrootlab.onmaru.identity.lifecycle;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface MemberLifecycleStore {

    Optional<MemberSummary> findActiveMemberBySessionHash(String sessionTokenHash, Instant now);

    boolean revokeSession(String sessionTokenHash, Instant now);

    Optional<MemberLifecycleStatus> requestDeletion(String sessionTokenHash, Instant now);

    boolean allowsLateWrite(UUID memberId);
}
