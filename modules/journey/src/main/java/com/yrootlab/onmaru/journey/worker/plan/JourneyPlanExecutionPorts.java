package com.yrootlab.onmaru.journey.worker.plan;

import com.yrootlab.onmaru.journey.worker.*;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.*;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Required production extension points. No permissive in-memory defaults are supplied. */
public final class JourneyPlanExecutionPorts {
    private JourneyPlanExecutionPorts() { }
    @FunctionalInterface public interface SafeQueryFactory {
        SafeQueryRequest create(JourneyWorkerRequest request, CandidatePayload candidates);
    }
    @FunctionalInterface public interface CurrentVersions {
        Versions current();
    }
    /** #809 stores the approved run/plan before the first callback can arrive. */
    @FunctionalInterface public interface ApprovedBindingStore {
        void bind(ApprovedPlanBinding binding, Instant deadline);
    }
    /** #810 issues an opaque reference with run/plan/filter/version/expiry binding. */
    @FunctionalInterface public interface QueryGrantIssuer {
        String issue(ApprovedPlanBinding binding, Instant deadline);
    }
    /** #809 issues a callback capability; returning null explicitly disables callback reporting. */
    @FunctionalInterface public interface ProgressCapabilityIssuer {
        String issue(ApprovedPlanBinding binding, Instant deadline);
    }
    /** #811 must validate artifacts/public eligibility before mapping to the authoritative result. */
    @FunctionalInterface public interface ExecutionResultMapper {
        JourneyWorkerPlan map(ApprovedPlanBinding binding, ExecutionResult result, CandidatePayload candidates);
    }
    /** Post-execution check against the live active public catalog without restricting AI to pre-fetched candidates. */
    @FunctionalInterface public interface PublicPlaceEligibilityChecker {
        Set<String> eligiblePlaceIds(List<String> placeIds, Filters approvedFilters);
    }
    /** Reads persisted pinned/excluded/last-confirmed place refs for safe-query construction. */
    @FunctionalInterface public interface ExplorationContextReader {
        ExplorationSafeContext read(UUID explorationId);
    }
    public record ExplorationSafeContext(
            List<String> pinnedPlaceIds,
            List<String> excludedPlaceIds,
            List<String> lastConfirmedPlaceIds) {
        public ExplorationSafeContext {
            pinnedPlaceIds = pinnedPlaceIds == null ? List.of() : List.copyOf(pinnedPlaceIds);
            excludedPlaceIds = excludedPlaceIds == null ? List.of() : List.copyOf(excludedPlaceIds);
            lastConfirmedPlaceIds = lastConfirmedPlaceIds == null ? List.of() : List.copyOf(lastConfirmedPlaceIds);
        }
        public static ExplorationSafeContext empty() {
            return new ExplorationSafeContext(List.of(), List.of(), List.of());
        }
    }
}
