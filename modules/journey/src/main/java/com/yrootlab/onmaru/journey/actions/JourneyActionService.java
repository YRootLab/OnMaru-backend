package com.yrootlab.onmaru.journey.actions;

import java.time.Clock;
import java.util.ArrayList;

public final class JourneyActionService {
    private final Clock clock;
    private final ResourceAvailability availability;

    public JourneyActionService(Clock clock, ResourceAvailability availability) {
        this.clock = clock;
        this.availability = availability;
    }

    public JourneyActionResult apply(JourneyActionState state, int baseVersion, JourneyAction action) {
        if (baseVersion != state.stateVersion()) {
            throw new JourneyActionVersionConflictException(state.stateVersion());
        }
        return switch (action) {
            case ResourceAction resource -> applyResource(state, resource);
            case ProposalAction proposal -> applyProposal(state, proposal);
        };
    }

    private JourneyActionResult applyResource(JourneyActionState state, ResourceAction action) {
        var pinned = new ArrayList<>(state.pinnedRefs());
        var excluded = new ArrayList<>(state.excludedRefs());
        var changed = switch (action.type()) {
            case PIN -> add(pinned, action.resourceRef(), 3);
            case UNPIN -> pinned.remove(action.resourceRef());
            case EXCLUDE -> {
                if (pinned.contains(action.resourceRef())) throw new PinnedResourceActionException(action.resourceRef());
                yield add(excluded, action.resourceRef(), 100);
            }
            case UNEXCLUDE -> excluded.remove(action.resourceRef());
            default -> throw new IllegalArgumentException("unsupported resource action");
        };
        if (!changed) return new JourneyActionResult(state, false);
        var ordered = state.orderedRefs().stream().filter(ref -> !excluded.contains(ref)).toList();
        var updated = new JourneyActionState(state.stateVersion() + 1, pinned, excluded, ordered, legs(ordered), null);
        return new JourneyActionResult(updated, true);
    }

    private JourneyActionResult applyProposal(JourneyActionState state, ProposalAction action) {
        var proposal = state.pendingProposal();
        if (proposal == null || !proposal.id().equals(action.proposalId())) throw new ProposalUnavailableException(action.proposalId());
        if (proposal.expiresAt().isBefore(clock.instant()) || proposal.baseVersion() != state.stateVersion()) {
            throw new ProposalUnavailableException(action.proposalId());
        }
        if (action.type() == ActionType.DISMISS_PROPOSAL) {
            return new JourneyActionResult(new JourneyActionState(state.stateVersion() + 1, state.pinnedRefs(), state.excludedRefs(), state.orderedRefs(), state.legs(), null), true);
        }
        if (proposal.orderedRefs().stream().anyMatch(ref -> !availability.isPublic(ref) || state.excludedRefs().contains(ref))) {
            throw new ProposalUnavailableException(action.proposalId());
        }
        var updated = new JourneyActionState(state.stateVersion() + 1, state.pinnedRefs(), state.excludedRefs(), proposal.orderedRefs(), legs(proposal.orderedRefs()), null);
        return new JourneyActionResult(updated, true);
    }

    private static boolean add(ArrayList<ResourceRef> refs, ResourceRef ref, int maximum) {
        if (refs.contains(ref)) return false;
        if (refs.size() == maximum) throw new IllegalArgumentException("resource limit exceeded");
        refs.add(ref);
        return true;
    }

    private static java.util.List<JourneyLeg> legs(java.util.List<ResourceRef> orderedRefs) {
        var legs = new ArrayList<JourneyLeg>();
        for (int index = 1; index < orderedRefs.size(); index++) legs.add(new JourneyLeg(orderedRefs.get(index - 1), orderedRefs.get(index)));
        return java.util.List.copyOf(legs);
    }
}
