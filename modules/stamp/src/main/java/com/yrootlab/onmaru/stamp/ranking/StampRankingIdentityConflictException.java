package com.yrootlab.onmaru.stamp.ranking;

public final class StampRankingIdentityConflictException extends RuntimeException {
    public StampRankingIdentityConflictException() {
        super("Anonymous ranking identity already exists");
    }
}
