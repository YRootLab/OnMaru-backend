package com.yrootlab.onmaru.web.internal.ai.progress;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;

import java.util.List;
import java.util.UUID;

final class ProgressTestFixtures {
    private ProgressTestFixtures() { }

    static PlanProposal plan() {
        var versions = new Versions("corpus-1", "sha256:" + "b".repeat(64), "dict-1", 7);
        var filters = new Filters("sha256:" + "c".repeat(64), true, null, null, null, List.of(), List.of());
        return new PlanProposal("ai-plan-proposal.v1", "journey-query-plan.v1", UUID.randomUUID(),
                Route.HYBRID, RetrievalMode.HYBRID, List.of("ROUTE_SELECTED"),
                new QuestionFrame("FIND", List.of(), List.of()),
                new RetrievalExpression(List.of(), List.of(), List.of()), List.of(), filters,
                "semantic", versions, new Limits(10, 10, 5, 5, 5), null,
                "sha256:" + "a".repeat(64));
    }
}
