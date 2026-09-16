package com.yrootlab.onmaru.journey.actions;

import java.util.UUID;

public record ProposalAction(ActionType type, UUID proposalId) implements JourneyAction {
    public ProposalAction {
        if (type != ActionType.APPLY_PROPOSAL && type != ActionType.DISMISS_PROPOSAL) {
            throw new IllegalArgumentException("proposal action type is required");
        }
        if (proposalId == null) {
            throw new IllegalArgumentException("proposalId is required");
        }
    }
}
