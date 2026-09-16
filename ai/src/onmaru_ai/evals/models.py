from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

from onmaru_ai.proposal import ProposalOutcome


class EvalModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)


class VersionPins(EvalModel):
    model: str = Field(min_length=1)
    prompt: str = Field(min_length=1)
    ranking: str = Field(min_length=1)
    dataset: str = Field(min_length=1)


class EvalThresholds(EvalModel):
    min_retrieval_recall_at_5: float = Field(alias="minRetrievalRecallAt5", ge=0, le=1)
    min_ndcg_at_3: float = Field(alias="minNdcgAt3", ge=0, le=1)
    min_claim_support: float = Field(alias="minClaimSupport", ge=0, le=1)
    min_safety: float = Field(alias="minSafety", ge=0, le=1)
    max_latency_p95_ms: int = Field(alias="maxLatencyP95Ms", ge=0)
    max_mean_cost_micros: int = Field(alias="maxMeanCostMicros", ge=0)
    max_per_run_cost_micros: int = Field(alias="maxPerRunCostMicros", ge=0)


class Relevance(EvalModel):
    ref: str = Field(min_length=1)
    grade: int = Field(ge=0, le=2)


class GoldExpectation(EvalModel):
    relevance: tuple[Relevance, ...]
    supported_evidence: dict[str, tuple[str, ...]] = Field(alias="supportedEvidence")
    allowed_refs: tuple[str, ...] = Field(alias="allowedRefs")
    pinned_refs: tuple[str, ...] = Field(alias="pinnedRefs")
    excluded_refs: tuple[str, ...] = Field(alias="excludedRefs")
    expected_outcome: ProposalOutcome = Field(alias="expectedOutcome")

    @model_validator(mode="after")
    def validate_reference_sets(self) -> GoldExpectation:
        allowed = set(self.allowed_refs)
        if len(allowed) != len(self.allowed_refs):
            raise ValueError("allowed refs must be unique")
        if len(set(self.pinned_refs)) != len(self.pinned_refs):
            raise ValueError("pinned refs must be unique")
        if len(set(self.excluded_refs)) != len(self.excluded_refs):
            raise ValueError("excluded refs must be unique")
        if not set(self.pinned_refs).issubset(allowed):
            raise ValueError("pinned refs must belong to allowed refs")
        if allowed.intersection(self.excluded_refs):
            raise ValueError("allowed and excluded refs must be disjoint")
        relevance_refs = tuple(item.ref for item in self.relevance)
        if len(relevance_refs) != len(set(relevance_refs)):
            raise ValueError("relevance refs must be unique")
        if not set(relevance_refs).issubset(allowed):
            raise ValueError("relevance refs must belong to allowed refs")
        if not set(self.supported_evidence).issubset(allowed):
            raise ValueError("supported evidence refs must belong to allowed refs")
        return self


class ActualReason(EvalModel):
    ref: str = Field(min_length=1)
    evidence_ids: tuple[str, ...] = Field(alias="evidenceIds", min_length=1, max_length=3)
    summary: str = Field(min_length=1, max_length=200)
    human_claim_supported: bool = Field(alias="humanClaimSupported")


class ActualResult(EvalModel):
    retrieved_refs: tuple[str, ...] = Field(alias="retrievedRefs", max_length=30)
    outcome: ProposalOutcome
    ordered_refs: tuple[str, ...] = Field(alias="orderedRefs", max_length=3)
    reasons: tuple[ActualReason, ...] = Field(max_length=3)
    latency_ms: int = Field(alias="latencyMs", ge=0)
    cost_micros: int = Field(alias="costMicros", ge=0)

    @model_validator(mode="after")
    def validate_result_shape(self) -> ActualResult:
        if len(self.retrieved_refs) != len(set(self.retrieved_refs)):
            raise ValueError("retrieved refs must be unique")
        reason_refs = tuple(reason.ref for reason in self.reasons)
        if self.outcome is ProposalOutcome.PROPOSE_BOARD:
            if not self.ordered_refs or not self.reasons:
                raise ValueError("board proposal must contain ordered refs and reasons")
            if len(self.ordered_refs) != len(set(self.ordered_refs)):
                raise ValueError("ordered refs must be unique")
            if reason_refs != self.ordered_refs:
                raise ValueError("reason refs must exactly match ordered refs")
        elif self.ordered_refs or self.reasons:
            raise ValueError("non-board outcome must not contain board data")
        return self


class EvaluationCase(EvalModel):
    id: str = Field(min_length=1)
    gold: GoldExpectation
    actual: ActualResult


class EvalDocument(EvalModel):
    schema_version: Literal["1.1"] = Field(alias="schemaVersion")
    versions: VersionPins
    thresholds: EvalThresholds
    cases: tuple[EvaluationCase, ...] = Field(min_length=1)

    @model_validator(mode="after")
    def unique_case_ids(self) -> EvalDocument:
        case_ids = tuple(item.id for item in self.cases)
        if len(case_ids) != len(set(case_ids)):
            raise ValueError("evaluation case ids must be unique")
        return self


class RatioMetric(EvalModel):
    numerator: int = Field(ge=0)
    denominator: int = Field(ge=0)
    value: float = Field(ge=0, le=1)


class SafetyMetric(EvalModel):
    passing_cases: int = Field(alias="passingCases", ge=0)
    total_cases: int = Field(alias="totalCases", ge=1)
    violations: int = Field(ge=0)
    value: float = Field(ge=0, le=1)


class EvalMetrics(EvalModel):
    retrieval_recall_at_5: RatioMetric = Field(alias="retrievalRecallAt5")
    ndcg_at_3: float = Field(alias="ndcgAt3", ge=0, le=1)
    evidence_id_precision: RatioMetric = Field(alias="evidenceIdPrecision")
    claim_support: RatioMetric = Field(alias="claimSupport")
    safety: SafetyMetric
    latency_p95_ms: int = Field(alias="latencyP95Ms", ge=0)
    mean_cost_micros: int = Field(alias="meanCostMicros", ge=0)
    max_cost_micros: int = Field(alias="maxCostMicros", ge=0)


class DatasetIdentity(EvalModel):
    input_sha256: str = Field(alias="inputSha256", pattern=r"^[a-f0-9]{64}$")
    gold_sha256: str = Field(alias="goldSha256", pattern=r"^[a-f0-9]{64}$")
    case_count: int = Field(alias="caseCount", ge=1)


class GateResult(EvalModel):
    name: Literal["quality", "evidenceFaithfulness", "safety", "latency", "cost"]
    passed: bool
    observed: dict[str, float | int]
    thresholds: dict[str, float | int]


class ReportComparison(EvalModel):
    compatible: bool
    metric_deltas: dict[str, float | int] = Field(alias="metricDeltas")


class EvalReport(EvalModel):
    schema_version: Literal["1.1"] = Field(alias="schemaVersion")
    versions: VersionPins
    dataset: DatasetIdentity
    metrics: EvalMetrics
    gates: tuple[GateResult, ...]
    overall_passed: bool = Field(alias="overallPassed")
    comparison: ReportComparison | None = None
