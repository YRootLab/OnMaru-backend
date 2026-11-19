package com.yrootlab.onmaru.integration.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.integration.ai.plan.*;
import com.yrootlab.onmaru.journey.worker.*;
import com.yrootlab.onmaru.journey.worker.plan.*;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class PlanExecuteAiProposalClientTests {
    private final AiPlanWireCodec codec=new AiPlanWireCodec();
    private final Instant now=Instant.parse("2026-11-08T00:00:00Z");

    @Test void approvesBindsAndPassesCapabilitiesBeforeExecuteThenDelegatesFinalMapping() throws Exception {
        var fixture=fixture(); var steps=new ArrayList<String>();
        var result=new ExecutionResult("ai-execute-result.v1",fixture.safe.runId(),fixture.plan.planHash(),fixture.safe.versions(),null,null);
        AiPlanExecuteClient transport=new AiPlanExecuteClient() {
            public PlanProposal plan(SafeQueryRequest request,CallContext context) { steps.add("plan"); assertThat(request).isEqualTo(fixture.safe); return fixture.plan; }
            public ExecutionResult execute(ApprovedQueryPlan request,CallContext context,String token) {
                steps.add("execute"); assertThat(request.queryGrantId()).isEqualTo("opaque-grant");
                assertThat(request.approvedPlanHash()).isEqualTo(fixture.plan.planHash()); assertThat(token).isEqualTo("progress-token"); return result;
            }
        };
        var client=new PlanExecuteAiProposalClient(transport,new DefaultPlanAuthorityGate(codec),
                (request,candidates)->fixture.safe, ()->fixture.safe.versions(),
                (binding,deadline)-> { steps.add("bind"); assertThat(deadline).isEqualTo(now.plusSeconds(20)); },
                (binding,deadline)-> { steps.add("grant"); return "opaque-grant"; },
                (binding,deadline)-> { steps.add("progress"); return "progress-token"; },
                (binding,execution,candidates)-> { steps.add("map"); assertThat(execution).isEqualTo(result); return new JourneyWorkerPlan(List.of("place-1"),"PROPOSAL"); },
                Clock.fixed(now,ZoneOffset.UTC));
        assertThat(client.propose(request(fixture.safe.runId()),new CandidatePayload("corpus-1",List.of()))).isEqualTo(new JourneyWorkerPlan(List.of("place-1"),"PROPOSAL"));
        assertThat(steps).containsExactly("plan","bind","grant","progress","execute","map");
        assertThatThrownBy(()->client.propose(request(fixture.safe.runId()),new CandidatePayload("corpus-1",List.of()))).isInstanceOf(AiProposalException.class);
        assertThat(steps).hasSize(6);
    }

    @Test void invalidAuthorityNeverIssuesCapabilitiesOrExecutes() throws Exception {
        var fixture=fixture(); var calls=new ArrayList<String>();
        var client=new PlanExecuteAiProposalClient(new AiPlanExecuteClient() {
            public PlanProposal plan(SafeQueryRequest request,CallContext context) { calls.add("plan"); return fixture.plan; }
            public ExecutionResult execute(ApprovedQueryPlan request,CallContext context,String token) { throw new AssertionError(); }
        }, new DefaultPlanAuthorityGate(codec),(request,candidates)->fixture.safe,
                ()->new Versions("new-corpus",fixture.safe.versions().configHash(),"questions-1",7),
                (binding,deadline)->{throw new AssertionError();},(b,d)->{throw new AssertionError();},
                (b,d)->{throw new AssertionError();},(b,r,c)->{throw new AssertionError();},Clock.fixed(now,ZoneOffset.UTC));
        assertThatThrownBy(()->client.propose(request(fixture.safe.runId()),new CandidatePayload("corpus-1",List.of()))).isInstanceOf(AiProposalException.class);
        assertThat(calls).containsExactly("plan");
    }

    @Test void classifiesSpringOwnedPortFailureAsUnavailableInsteadOfMalformedAiResponse() throws Exception {
        var fixture=fixture();
        var client=new PlanExecuteAiProposalClient(new AiPlanExecuteClient() {
            public PlanProposal plan(SafeQueryRequest request,CallContext context) { return fixture.plan; }
            public ExecutionResult execute(ApprovedQueryPlan request,CallContext context,String token) { throw new AssertionError(); }
        }, new DefaultPlanAuthorityGate(codec),(request,candidates)->fixture.safe,
                ()->fixture.safe.versions(), (binding,deadline)->{throw new IllegalStateException("database unavailable");},
                (b,d)->{throw new AssertionError();}, (b,d)->{throw new AssertionError();},
                (b,r,c)->{throw new AssertionError();},Clock.fixed(now,ZoneOffset.UTC));

        assertThatThrownBy(()->client.propose(request(fixture.safe.runId()),new CandidatePayload("corpus-1",List.of())))
                .isInstanceOfSatisfying(AiProposalException.class,
                        exception -> assertThat(exception.degradedReason()).isEqualTo(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE));
    }

    @Test void clarifyOrScopeReturnsClarificationWithoutIssuingCapabilitiesOrExecuting() throws Exception {
        var fixture = fixture();
        var unsignedClarify = new PlanProposal(
                "ai-plan-proposal.v1",
                "ai-query-plan.v1",
                fixture.safe().runId(),
                Route.CLARIFY_OR_SCOPE,
                RetrievalMode.NONE,
                List.of("AMBIGUOUS_TARGET"),
                new QuestionFrame("RECOMMEND", List.of(), List.of("QUIET")),
                new RetrievalExpression(List.of("QUIET"), List.of(), List.of()),
                List.of(),
                fixture.safe().authorityBounds().filters(),
                null,
                fixture.safe().versions(),
                fixture.safe().limits(),
                new Clarification("AMBIGUOUS_TARGET", "CHOOSE_TARGET"),
                "sha256:" + "0".repeat(64));
        String clarifyHash = codec.planHash(unsignedClarify);
        var clarifyPlan = new PlanProposal(
                unsignedClarify.schemaVersion(),
                unsignedClarify.planVersion(),
                unsignedClarify.runId(),
                unsignedClarify.route(),
                unsignedClarify.retrievalMode(),
                unsignedClarify.reasonCodes(),
                unsignedClarify.questionFrame(),
                unsignedClarify.retrievalExpression(),
                unsignedClarify.requestedCanonicalFields(),
                unsignedClarify.proposedFilters(),
                unsignedClarify.semanticQuery(),
                unsignedClarify.versions(),
                unsignedClarify.limits(),
                unsignedClarify.clarification(),
                clarifyHash);

        var client = new PlanExecuteAiProposalClient(
                new AiPlanExecuteClient() {
                    @Override public PlanProposal plan(SafeQueryRequest request, CallContext context) { return clarifyPlan; }
                    @Override public ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String token) {
                        throw new AssertionError("execute must not run on CLARIFY_OR_SCOPE");
                    }
                },
                new DefaultPlanAuthorityGate(codec),
                (request, candidates) -> fixture.safe(),
                () -> fixture.safe().versions(),
                (binding, deadline) -> { throw new AssertionError("bind must not run on CLARIFY_OR_SCOPE"); },
                (binding, deadline) -> { throw new AssertionError("grant must not run on CLARIFY_OR_SCOPE"); },
                (binding, deadline) -> { throw new AssertionError("progress must not run on CLARIFY_OR_SCOPE"); },
                (binding, result, candidates) -> { throw new AssertionError("mapper must not run on CLARIFY_OR_SCOPE"); },
                Clock.fixed(now, ZoneOffset.UTC));

        var plan = client.propose(request(fixture.safe().runId()), new CandidatePayload("corpus-1", List.of()));
        assertThat(plan.outcome()).isEqualTo("CLARIFICATION_REQUIRED");
        assertThat(plan.orderedRefs()).isEmpty();
        assertThat(plan.clarification()).isNotNull();
        assertThat(plan.clarification().id()).isEqualTo("CHOOSE_TARGET");
        assertThat(plan.clarification().reason()).isEqualTo("AMBIGUOUS_TARGET");
    }

    @Test void boundedReservationMapEvictsOldestAfterTenThousandRunsWhileBlockingRecentDuplicates() throws Exception {
        var fixture = fixture();
        var client = new PlanExecuteAiProposalClient(
                new AiPlanExecuteClient() {
                    @Override public PlanProposal plan(SafeQueryRequest request, CallContext context) {
                        return new PlanProposal(
                                fixture.plan().schemaVersion(), fixture.plan().planVersion(), request.runId(),
                                fixture.plan().route(), fixture.plan().retrievalMode(), fixture.plan().reasonCodes(),
                                fixture.plan().questionFrame(), fixture.plan().retrievalExpression(),
                                fixture.plan().requestedCanonicalFields(), fixture.plan().proposedFilters(),
                                fixture.plan().semanticQuery(), fixture.plan().versions(), fixture.plan().limits(),
                                fixture.plan().clarification(),
                                codec.planHash(new PlanProposal(
                                        fixture.plan().schemaVersion(), fixture.plan().planVersion(), request.runId(),
                                        fixture.plan().route(), fixture.plan().retrievalMode(), fixture.plan().reasonCodes(),
                                        fixture.plan().questionFrame(), fixture.plan().retrievalExpression(),
                                        fixture.plan().requestedCanonicalFields(), fixture.plan().proposedFilters(),
                                        fixture.plan().semanticQuery(), fixture.plan().versions(), fixture.plan().limits(),
                                        fixture.plan().clarification(), fixture.plan().planHash())));
                    }
                    @Override public ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String token) {
                        return new ExecutionResult("ai-execute-result.v1", request.runId(), request.approvedPlanHash(),
                                request.versions(), null, null);
                    }
                },
                new DefaultPlanAuthorityGate(codec),
                (request, candidates) -> new SafeQueryRequest(
                        fixture.safe().schemaVersion(), request.runId(), fixture.safe().safeQuery(),
                        fixture.safe().confirmedContext(), fixture.safe().authorityBounds(),
                        fixture.safe().versions(), fixture.safe().limits()),
                () -> fixture.safe().versions(),
                (binding, deadline) -> { },
                (binding, deadline) -> "opaque-grant",
                (binding, deadline) -> "progress-token",
                (binding, execution, candidates) -> new JourneyWorkerPlan(List.of("place-1"), "PROPOSAL"),
                Clock.fixed(now, ZoneOffset.UTC));

        UUID firstRunId = UUID.randomUUID();
        assertThat(client.propose(request(firstRunId), new CandidatePayload("corpus-1", List.of())).outcome())
                .isEqualTo("PROPOSAL");

        UUID latestRunId = firstRunId;
        for (int i = 0; i < 10_005; i++) {
            latestRunId = UUID.randomUUID();
            assertThat(client.propose(request(latestRunId), new CandidatePayload("corpus-1", List.of())).outcome())
                    .isEqualTo("PROPOSAL");
        }

        UUID duplicateRecentRunId = latestRunId;
        assertThatThrownBy(() -> client.propose(request(duplicateRecentRunId), new CandidatePayload("corpus-1", List.of())))
                .isInstanceOfSatisfying(AiProposalException.class,
                        exception -> assertThat(exception.degradedReason()).isEqualTo(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE));

        // Oldest runId was evicted after exceeding 10,000 reservations, so worker does not permanently wedge.
        assertThat(client.propose(request(firstRunId), new CandidatePayload("corpus-1", List.of())).outcome())
                .isEqualTo("PROPOSAL");
    }

    @Test void preservesQuotaExceededWithRetryAfterAndRejectsStaleHeartbeatOrZeroFacts() throws Exception {
        var fixture = fixture();
        var quotaClient = new PlanExecuteAiProposalClient(
                new AiPlanExecuteClient() {
                    @Override public PlanProposal plan(SafeQueryRequest request, CallContext context) { return fixture.plan(); }
                    @Override public ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String token) {
                        throw new AiProposalException(WorkerDegradedReason.AI_QUOTA_EXCEEDED, 15_000L);
                    }
                },
                new DefaultPlanAuthorityGate(codec),
                (request, candidates) -> fixture.safe(),
                () -> fixture.safe().versions(),
                (binding, deadline) -> { },
                (binding, deadline) -> "opaque-grant",
                (binding, deadline) -> "progress-token",
                (binding, execution, candidates) -> { throw new AssertionError(); },
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> quotaClient.propose(request(fixture.safe().runId()), new CandidatePayload("corpus-1", List.of())))
                .isInstanceOfSatisfying(AiProposalException.class, exception -> {
                    assertThat(exception.degradedReason()).isEqualTo(WorkerDegradedReason.AI_QUOTA_EXCEEDED);
                    assertThat(exception.retryAfterMs()).isEqualTo(15_000L);
                });

        var staleOrZeroFactsClient = new PlanExecuteAiProposalClient(
                new AiPlanExecuteClient() {
                    @Override public PlanProposal plan(SafeQueryRequest request, CallContext context) {
                        throw new AiProposalException(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE);
                    }
                    @Override public ExecutionResult execute(ApprovedQueryPlan request, CallContext context, String token) {
                        throw new AssertionError();
                    }
                },
                new DefaultPlanAuthorityGate(codec),
                (request, candidates) -> fixture.safe(),
                () -> fixture.safe().versions(),
                (binding, deadline) -> { throw new AssertionError(); },
                (b, d) -> { throw new AssertionError(); },
                (b, d) -> { throw new AssertionError(); },
                (b, r, c) -> { throw new AssertionError(); },
                Clock.fixed(now, ZoneOffset.UTC));

        assertThatThrownBy(() -> staleOrZeroFactsClient.propose(request(fixture.safe().runId()), new CandidatePayload("corpus-1", List.of())))
                .isInstanceOfSatisfying(AiProposalException.class, exception ->
                        assertThat(exception.degradedReason()).isEqualTo(WorkerDegradedReason.AI_SERVICE_UNAVAILABLE));
    }

    private JourneyWorkerRequest request(UUID runId) { return new JourneyWorkerRequest(runId,UUID.randomUUID(),"member:1",1,"corpus-1","SEOUL","private raw query",1,"req-808","1".repeat(32),now.plusSeconds(20)); }
    private record Fixture(SafeQueryRequest safe,PlanProposal plan) { }
    private Fixture fixture() throws Exception {
        try(var input=getClass().getResourceAsStream("/ai-plan-execute/accepted-plan-sql_only.json")) {
            var tree=new ObjectMapper().readTree(input);
            return new Fixture(codec.readSafeQuery(tree.get("context").get("request").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),codec.readPlan(tree.get("payload").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
