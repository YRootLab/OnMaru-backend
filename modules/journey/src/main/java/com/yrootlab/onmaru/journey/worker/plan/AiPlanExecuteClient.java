package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;

public interface AiPlanExecuteClient {
    PlanProposal plan(SafeQueryRequest request, CallContext context);
    ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String progressToken);
}
