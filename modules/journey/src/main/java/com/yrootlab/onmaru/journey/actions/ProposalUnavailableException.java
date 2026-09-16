package com.yrootlab.onmaru.journey.actions;

import java.util.UUID;

public final class ProposalUnavailableException extends RuntimeException {
    private final UUID proposalId;

    public ProposalUnavailableException(UUID proposalId) {
        super("proposal is unavailable: " + proposalId);
        this.proposalId = proposalId;
    }

    public UUID proposalId() { return proposalId; }
}
