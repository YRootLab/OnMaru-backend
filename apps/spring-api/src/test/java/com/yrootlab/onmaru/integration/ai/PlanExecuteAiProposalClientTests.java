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

    private JourneyWorkerRequest request(UUID runId) { return new JourneyWorkerRequest(runId,UUID.randomUUID(),"member:1",1,"corpus-1","SEOUL","private raw query",1,"req-808","1".repeat(32),now.plusSeconds(20)); }
    private record Fixture(SafeQueryRequest safe,PlanProposal plan) { }
    private Fixture fixture() throws Exception {
        try(var input=getClass().getResourceAsStream("/ai-plan-execute/accepted-plan-sql_only.json")) {
            var tree=new ObjectMapper().readTree(input);
            return new Fixture(codec.readSafeQuery(tree.get("context").get("request").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),codec.readPlan(tree.get("payload").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
    }
}
