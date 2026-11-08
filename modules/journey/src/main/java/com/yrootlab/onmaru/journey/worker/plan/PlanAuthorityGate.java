package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;

public interface PlanAuthorityGate {
    ApprovedPlanBinding approve(SafeQueryRequest request, PlanProposal proposal, Versions currentVersions);
}
