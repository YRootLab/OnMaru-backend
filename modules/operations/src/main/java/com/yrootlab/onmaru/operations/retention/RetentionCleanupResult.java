package com.yrootlab.onmaru.operations.retention;

public record RetentionCleanupResult(
        int expiredGuests,
        int expiredSessions,
        int expiredRuns,
        int expiredProposals,
        int inactiveRevisions,
        int memberDeletionResources,
        int ledgerEntries
) {

    public RetentionCleanupResult {
        requireNonNegative(expiredGuests, "expiredGuests");
        requireNonNegative(expiredSessions, "expiredSessions");
        requireNonNegative(expiredRuns, "expiredRuns");
        requireNonNegative(expiredProposals, "expiredProposals");
        requireNonNegative(inactiveRevisions, "inactiveRevisions");
        requireNonNegative(memberDeletionResources, "memberDeletionResources");
        requireNonNegative(ledgerEntries, "ledgerEntries");
    }

    private static void requireNonNegative(int value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
