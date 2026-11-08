package com.yrootlab.onmaru.integration.ai.grant;

import java.time.Instant;
import java.util.function.Function;

public interface QueryGrantStore {
    void insert(QueryGrant grant);
    /** Locks grant and run, validates active/deadline/expiry/use, applies operation, then consumes atomically. */
    <T> T consume(String tokenHash, Instant now, Function<QueryGrant,T> operation);
}
