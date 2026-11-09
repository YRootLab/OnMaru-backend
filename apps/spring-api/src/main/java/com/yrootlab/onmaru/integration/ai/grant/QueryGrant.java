package com.yrootlab.onmaru.integration.ai.grant;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Sanitized, immutable authority metadata; never contains the opaque reference or query text. */
public record QueryGrant(String tokenHash, UUID runId, String planHash, Filters filters,
                         List<CanonicalField> canonicalFields, Versions versions,
                         int maxPlaces, Instant createdAt, Instant expiresAt) {
    public QueryGrant { canonicalFields = List.copyOf(canonicalFields); }
}
