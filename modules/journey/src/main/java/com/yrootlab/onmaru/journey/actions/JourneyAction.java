package com.yrootlab.onmaru.journey.actions;

public sealed interface JourneyAction permits ResourceAction, ProposalAction {
    ActionType type();
}
