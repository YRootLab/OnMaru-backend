package com.yrootlab.onmaru.integration.ai;

import com.yrootlab.onmaru.integration.ai.plan.AiPlanVersionsProperties;
import com.yrootlab.onmaru.integration.ai.plan.AiPlanWireCodec;
import com.yrootlab.onmaru.integration.ai.plan.ConfigurableCurrentVersions;
import com.yrootlab.onmaru.integration.ai.plan.DefaultPlanAuthorityGate;
import com.yrootlab.onmaru.integration.ai.plan.DefaultSafeQueryFactory;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ApprovedPlanBinding;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CanonicalField;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Limits;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.PlanProposal;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.QuestionFrame;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.RetrievalExpression;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.RetrievalMode;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Route;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.SafeQueryRequest;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationSafeContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSafeQueryFactoryTests {

    private final AiPlanWireCodec codec = new AiPlanWireCodec();

    @Test
    void buildsUnrestrictedSafeQueryWithRedactedPiiAndDeterministicFilterHash() {
        var versions = new ConfigurableCurrentVersions(AiPlanVersionsProperties.defaults());
        var explorationId = UUID.randomUUID();
        var runId = UUID.randomUUID();
        var factory = new DefaultSafeQueryFactory(
                id -> {
                    assertThat(id).isEqualTo(explorationId);
                    return new ExplorationSafeContext(
                            List.of("p-pinned-1", "p-pinned-2", "invalid space"),
                            List.of("p-pinned-2", "p-excluded-1", "p-excluded-1"),
                            List.of("p-last-1"));
                },
                versions,
                codec);

        var request = new JourneyWorkerRequest(
                runId,
                explorationId,
                "MEMBER:123",
                1,
                "catalog-current",
                "kr-45-jeonju",
                "user@example.com 010-1234-5678 900101-1234567\n\t조용한   전주 한옥\u0000 코스",
                1,
                "req-1",
                "trace-1",
                Instant.parse("2026-11-17T00:00:30Z"));

        SafeQueryRequest safeQuery = factory.create(request, new CandidatePayload("catalog-current", List.of()));

        assertThat(safeQuery.schemaVersion()).isEqualTo("ai-safe-query.v1");
        assertThat(safeQuery.runId()).isEqualTo(runId);
        assertThat(safeQuery.safeQuery()).isEqualTo("[REDACTED] [REDACTED] [REDACTED] 조용한 전주 한옥 코스");
        assertThat(safeQuery.confirmedContext().regionCode()).isEqualTo("kr-45-jeonju");
        assertThat(safeQuery.confirmedContext().pinnedPlaceIds()).containsExactly("p-pinned-1", "p-pinned-2");
        assertThat(safeQuery.confirmedContext().excludedPlaceIds()).containsExactly("p-excluded-1");
        assertThat(safeQuery.confirmedContext().lastConfirmedPlaceIds()).containsExactly("p-last-1");

        Filters filters = safeQuery.authorityBounds().filters();
        assertThat(filters.publicOnly()).isTrue();
        assertThat(filters.placeIds()).isNull();
        assertThat(filters.categoryCodes()).isNull();
        assertThat(filters.regionCodes()).containsExactly("kr-45-jeonju");
        assertThat(filters.pinnedPlaceIds()).containsExactly("p-pinned-1", "p-pinned-2");
        assertThat(filters.excludedPlaceIds()).containsExactly("p-excluded-1");
        assertThat(filters.filterHash()).startsWith("sha256:");
        assertThat(safeQuery.authorityBounds().allowedCanonicalFields())
                .containsExactlyInAnyOrder(CanonicalField.values());
        assertThat(safeQuery.limits()).isEqualTo(new Limits(20, 20, 30, 12, 5));
        assertThat(safeQuery.versions()).isEqualTo(new Versions(
                AiPlanVersionsProperties.DEFAULT_CORPUS_REVISION,
                AiPlanVersionsProperties.DEFAULT_CONFIG_HASH,
                AiPlanVersionsProperties.DEFAULT_QUESTION_DICTIONARY_VERSION,
                AiPlanVersionsProperties.DEFAULT_SOURCE_REVOCATION_SEQUENCE));
    }

    @Test
    void capsSafeQueryCodePointsAndFallsBackWhenBlankAfterSanitization() {
        var versions = new ConfigurableCurrentVersions(AiPlanVersionsProperties.defaults());
        var factory = new DefaultSafeQueryFactory(ignored -> ExplorationSafeContext.empty(), versions, codec);

        var longRaw = "가".repeat(300);
        SafeQueryRequest capped = factory.create(workerRequest(longRaw, null), new CandidatePayload("r", List.of()));
        assertThat(capped.safeQuery().codePointCount(0, capped.safeQuery().length())).isEqualTo(240);

        SafeQueryRequest blank = factory.create(workerRequest("\u0000\u0007   \n", "bad region"),
                new CandidatePayload("r", List.of()));
        assertThat(blank.safeQuery()).isEqualTo("여행 코스 추천");
        assertThat(blank.confirmedContext().regionCode()).isNull();
        assertThat(blank.authorityBounds().filters().regionCodes()).isNull();
    }

    @Test
    void generatedSafeQueryIsAcceptedByDefaultPlanAuthorityGate() throws Exception {
        var props = new AiPlanVersionsProperties(
                "corpus-live-2026-11",
                "sha256:" + "b".repeat(64),
                "questions-1",
                12L);
        var versions = new ConfigurableCurrentVersions(props);
        var factory = new DefaultSafeQueryFactory(
                ignored -> new ExplorationSafeContext(List.of("p-1"), List.of("p-2"), List.of()),
                versions,
                codec);
        SafeQueryRequest safeQuery = factory.create(
                workerRequest("전주 한옥 산책 코스", "kr-45-jeonju"),
                new CandidatePayload("corpus-live-2026-11", List.of()));

        Filters bounds = safeQuery.authorityBounds().filters();
        var proposalWithoutHash = new PlanProposal(
                "ai-plan-proposal.v1",
                "ai-query-plan.v1",
                safeQuery.runId(),
                Route.SQL_PLUS_RETRIEVAL,
                RetrievalMode.HYBRID,
                List.of("COMPOSITE_QUERY"),
                new QuestionFrame("RECOMMEND", List.of(), List.of("HANOK", "QUIET")),
                new RetrievalExpression(List.of("HANOK", "QUIET"), List.of(), List.of()),
                List.of(CanonicalField.CATEGORY_CODE, CanonicalField.PUBLIC_ADDRESS),
                bounds,
                "전주 조용한 한옥 산책",
                safeQuery.versions(),
                safeQuery.limits(),
                null,
                null);
        String planHash = codec.planHash(proposalWithoutHash);
        PlanProposal signedPlan = new PlanProposal(
                proposalWithoutHash.schemaVersion(),
                proposalWithoutHash.planVersion(),
                proposalWithoutHash.runId(),
                proposalWithoutHash.route(),
                proposalWithoutHash.retrievalMode(),
                proposalWithoutHash.reasonCodes(),
                proposalWithoutHash.questionFrame(),
                proposalWithoutHash.retrievalExpression(),
                proposalWithoutHash.requestedCanonicalFields(),
                proposalWithoutHash.proposedFilters(),
                proposalWithoutHash.semanticQuery(),
                proposalWithoutHash.versions(),
                proposalWithoutHash.limits(),
                proposalWithoutHash.clarification(),
                planHash);

        ApprovedPlanBinding approved = new DefaultPlanAuthorityGate(codec)
                .approve(safeQuery, signedPlan, versions.current());

        assertThat(approved.plan().planHash()).isEqualTo(planHash);
        assertThat(approved.plan().proposedFilters().placeIds()).isNull();
    }

    private JourneyWorkerRequest workerRequest(String query, String regionCode) {
        return new JourneyWorkerRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "MEMBER:1",
                0,
                "corpus-1",
                regionCode,
                query,
                1,
                "req-1",
                "trace-1",
                Instant.parse("2026-11-17T00:00:30Z"));
    }
}
