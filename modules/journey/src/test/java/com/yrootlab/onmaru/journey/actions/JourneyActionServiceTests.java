package com.yrootlab.onmaru.journey.actions;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JourneyActionServiceTests {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
    private final JourneyActionService service = new JourneyActionService(CLOCK, ref -> !ref.id().equals("hidden"));

    @Test
    void rejectsAStaleBaseVersionBeforeChangingState() {
        var state = JourneyActionState.empty(2);

        assertThatThrownBy(() -> service.apply(state, 1, new ResourceAction(ActionType.PIN, place("p1"))))
                .isInstanceOf(JourneyActionVersionConflictException.class)
                .hasMessageContaining("2");

        assertThat(state.stateVersion()).isEqualTo(2);
    }

    @Test
    void rejectsExcludeForAPinnedPlace() {
        var pinnedState = service.apply(JourneyActionState.empty(0), 0, new ResourceAction(ActionType.PIN, place("p1"))).state();

        assertThatThrownBy(() -> service.apply(pinnedState, 1, new ResourceAction(ActionType.EXCLUDE, place("p1"))))
                .isInstanceOf(PinnedResourceActionException.class);
    }

    @Test
    void leavesVersionUnchangedForAnAlreadyAppliedPin() {
        var pinned = service.apply(JourneyActionState.empty(0), 0, new ResourceAction(ActionType.PIN, place("p1"))).state();

        var result = service.apply(pinned, 1, new ResourceAction(ActionType.PIN, place("p1")));

        assertThat(result.changed()).isFalse();
        assertThat(result.state().stateVersion()).isEqualTo(1);
    }

    @Test
    void appliesOnlyAProposalWhosePublicOrderIsStillValidAndRecalculatesLegs() {
        var proposal = new JourneyProposal(UUID.randomUUID(), 0, List.of(place("p1"), place("p2")), CLOCK.instant().plusSeconds(60));
        var state = JourneyActionState.empty(0).withPendingProposal(proposal);

        var result = service.apply(state, 0, new ProposalAction(ActionType.APPLY_PROPOSAL, proposal.id()));

        assertThat(result.changed()).isTrue();
        assertThat(result.state().orderedRefs()).containsExactly(place("p1"), place("p2"));
        assertThat(result.state().legs()).containsExactly(new JourneyLeg(place("p1"), place("p2")));
        assertThat(result.state().pendingProposal()).isNull();
    }

    @Test
    void rejectsApplyWhenAProposedPlaceIsNoLongerPublic() {
        var proposal = new JourneyProposal(UUID.randomUUID(), 0, List.of(place("p1"), place("hidden")), CLOCK.instant().plusSeconds(60));
        var state = JourneyActionState.empty(0).withPendingProposal(proposal);

        assertThatThrownBy(() -> service.apply(state, 0, new ProposalAction(ActionType.APPLY_PROPOSAL, proposal.id())))
                .isInstanceOf(ProposalUnavailableException.class);

        assertThat(state.stateVersion()).isZero();
        assertThat(state.pendingProposal()).isEqualTo(proposal);
    }

    @Test
    void excludesInvalidatePendingProposalAndRecalculateCommittedLegs() {
        var proposal = new JourneyProposal(UUID.randomUUID(), 0, List.of(place("p1")), CLOCK.instant().plusSeconds(60));
        var state = new JourneyActionState(0, List.of(), List.of(), List.of(place("p1"), place("p2")),
                List.of(new JourneyLeg(place("p1"), place("p2"))), proposal);

        var result = service.apply(state, 0, new ResourceAction(ActionType.EXCLUDE, place("p1")));

        assertThat(result.state().stateVersion()).isEqualTo(1);
        assertThat(result.state().pendingProposal()).isNull();
        assertThat(result.state().orderedRefs()).containsExactly(place("p2"));
        assertThat(result.state().legs()).isEmpty();
    }

    private static ResourceRef place(String id) {
        return new ResourceRef("PLACE", id);
    }
}
