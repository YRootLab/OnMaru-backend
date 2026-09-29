package com.yrootlab.onmaru.identity.lifecycle;

import java.time.Instant;
import java.util.UUID;

/** Shared access boundary used by OAuth login and existing member sessions. */
@FunctionalInterface
public interface MemberAccessPolicy {

    boolean allows(UUID memberId, Instant now);

    static MemberAccessPolicy allowAll() {
        return (memberId, now) -> true;
    }

    static MemberAccessPolicy rejectAll() {
        return (memberId, now) -> false;
    }
}
