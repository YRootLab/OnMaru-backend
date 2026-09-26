package com.yrootlab.onmaru.stamp;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface StampStore {
    List<StampDefinition> definitions();

    StampCheckInResult record(UUID memberId, VerifiedPlace place, Instant now, int accuracyMeters);

    StampBook book(UUID memberId);
}
