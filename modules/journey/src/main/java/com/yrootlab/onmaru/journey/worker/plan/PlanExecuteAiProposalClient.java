package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.exploration.ExplorationClarification;
import com.yrootlab.onmaru.journey.worker.*;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.*;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Spring-owned orchestration. A reserved run is never replayed after ambiguous downstream effects. */
public final class PlanExecuteAiProposalClient implements AiProposalClient {
    private final AiPlanExecuteClient client;
    private final PlanAuthorityGate gate;
    private final SafeQueryFactory requests;
    private final CurrentVersions versions;
    private final ApprovedBindingStore bindings;
    private final QueryGrantIssuer grants;
    private final ProgressCapabilityIssuer progress;
    private final ExecutionResultMapper mapper;
    private final Clock clock;
    private static final int MAX_RESERVATIONS=10_000;
    private final Map<UUID,Boolean> attemptedRuns=new LinkedHashMap<>(256,0.75f,false) {
        @Override protected boolean removeEldestEntry(Map.Entry<UUID,Boolean> eldest) { return size()>MAX_RESERVATIONS; }
    };

    public PlanExecuteAiProposalClient(AiPlanExecuteClient client, PlanAuthorityGate gate, SafeQueryFactory requests,
            CurrentVersions versions, ApprovedBindingStore bindings, QueryGrantIssuer grants,
            ProgressCapabilityIssuer progress, ExecutionResultMapper mapper, Clock clock) {
        this.client=client;this.gate=gate;this.requests=requests;this.versions=versions;this.bindings=bindings;
        this.grants=grants;this.progress=progress;this.mapper=mapper;this.clock=clock;
    }

    @Override public boolean requiresCandidatePreload() { return false; }

    @Override public JourneyWorkerPlan propose(JourneyWorkerRequest request,CandidatePayload candidates) {
        try {
            requireBeforeDeadline(request);
            reserve(request.runId());
            var context=new CallContext(request.requestId(),request.runId(),request.traceId(),request.deadlineAt());
            var safe=requests.create(request,candidates);
            if(!request.runId().equals(safe.runId())) throw invalid();
            var plan=client.plan(safe,context);
            var binding=gate.approve(safe,plan,versions.current());
            if(plan.route()==Route.CLARIFY_OR_SCOPE) {
                return JourneyWorkerPlan.clarificationRequired(toClarification(plan.clarification()));
            }
            bindings.bind(binding, request.deadlineAt());
            String grant=plan.route().requiresLookup()?grants.issue(binding,request.deadlineAt()):null;
            String capability=progress.issue(binding,request.deadlineAt());
            requireBeforeDeadline(request);
            gate.approve(safe,plan,versions.current());
            var approved=binding.forExecution(grant);
            var result=client.execute(approved,context,capability);
            requireBeforeDeadline(request);
            if(!plan.versions().equals(versions.current()) || !plan.runId().equals(result.runId())
                    || !plan.planHash().equals(result.planHash()) || !plan.versions().equals(result.versions())) throw invalid();
            return mapper.map(binding,result,candidates);
        } catch(AiProposalException e) { throw e; }
        catch(RuntimeException e) { throw unavailable(); }
    }

    private synchronized void reserve(UUID runId) {
        if(runId==null || attemptedRuns.containsKey(runId))
            throw new AiProposalException(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE);
        attemptedRuns.put(runId, Boolean.TRUE);
    }
    private static ExplorationClarification toClarification(Clarification clarification) {
        String code=clarification!=null && clarification.questionCode()!=null ? clarification.questionCode() : "SUPPORTED_TRAVEL_SCOPE";
        String reason=clarification!=null && clarification.reason()!=null ? clarification.reason() : "UNSUPPORTED_SCOPE";
        String question=switch(code) {
            case "CHOOSE_TARGET" -> "어떤 장소를 기준으로 안내해 드릴까요?";
            case "RESOLVE_CONDITIONS" -> "서로 다른 조건 중 어떤 기준을 우선할까요?";
            case "PRESERVE_MEANING" -> "개인정보나 민감 정보 없이 여행 조건을 다시 입력해 주세요.";
            default -> "안내 가능한 여행 지역이나 주제를 조금 더 구체적으로 알려주시겠어요?";
        };
        return new ExplorationClarification(code,reason,question,true);
    }
    private void requireBeforeDeadline(JourneyWorkerRequest request) {
        if(request==null || request.deadlineAt()==null || !clock.instant().isBefore(request.deadlineAt()))
            throw new AiProposalException(WorkerDegradedReason.AI_TIMEOUT);
    }
    private static AiProposalException invalid() { return new AiProposalException(WorkerDegradedReason.AI_INVALID_RESPONSE); }
    private static AiProposalException unavailable() { return new AiProposalException(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE); }
}
