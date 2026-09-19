from __future__ import annotations

import hashlib
import json
import math
from collections.abc import Mapping

from pydantic import ValidationError

from onmaru_ai.proposal import ProposalOutcome

from .models import (
    DatasetIdentity,
    EvalDocument,
    EvalMetrics,
    EvalReport,
    EvaluationCase,
    GateResult,
    RatioMetric,
    ReportComparison,
    SafetyMetric,
)


def load_eval_document(value: object) -> EvalDocument:
    try:
        return EvalDocument.model_validate(value)
    except ValidationError as error:
        raise ValueError("invalid evaluation document") from error


def evaluate_document(value: object) -> EvalReport:
    document = load_eval_document(value)
    recall_numerator = 0
    recall_denominator = 0
    ndcg_values: list[float] = []
    matching_evidence_ids = 0
    proposed_evidence_ids = 0
    supported_claims = 0
    proposed_claims = 0
    passing_safety_cases = 0
    safety_violations = 0
    latencies: list[int] = []
    costs: list[int] = []

    for case in document.cases:
        grades = {item.ref: item.grade for item in case.gold.relevance if item.grade > 0}
        retrieved = _unique_refs(case.actual.retrieved_refs)[:5]
        recall_numerator += sum(ref in retrieved for ref in grades)
        recall_denominator += len(grades)
        if grades:
            ndcg_values.append(_ndcg(case.actual.retrieved_refs[:3], grades))

        for reason in case.actual.reasons:
            expected = set(case.gold.supported_evidence.get(reason.ref, ()))
            for evidence_id in reason.evidence_ids:
                proposed_evidence_ids += 1
                matching_evidence_ids += evidence_id in expected
            proposed_claims += 1
            supported_claims += reason.human_claim_supported

        violations = _safety_violations(case)
        safety_violations += violations
        passing_safety_cases += violations == 0
        latencies.append(case.actual.latency_ms)
        costs.append(case.actual.cost_micros)

    recall = _ratio(recall_numerator, recall_denominator)
    evidence_id_precision = _ratio(matching_evidence_ids, proposed_evidence_ids)
    claim_support = _ratio(supported_claims, proposed_claims)
    safety_value = passing_safety_cases / len(document.cases)
    metrics = EvalMetrics(
        retrievalRecallAt5=RatioMetric(
            numerator=recall_numerator,
            denominator=recall_denominator,
            value=_rounded(recall),
        ),
        ndcgAt3=_rounded(sum(ndcg_values) / len(ndcg_values) if ndcg_values else 1.0),
        evidenceIdPrecision=RatioMetric(
            numerator=matching_evidence_ids,
            denominator=proposed_evidence_ids,
            value=_rounded(evidence_id_precision),
        ),
        claimSupport=RatioMetric(
            numerator=supported_claims,
            denominator=proposed_claims,
            value=_rounded(claim_support),
        ),
        safety=SafetyMetric(
            passingCases=passing_safety_cases,
            totalCases=len(document.cases),
            violations=safety_violations,
            value=_rounded(safety_value),
        ),
        latencyP95Ms=_nearest_rank_percentile(latencies, 0.95),
        meanCostMicros=round(sum(costs) / len(costs)),
        maxCostMicros=max(costs),
    )
    input_hash = _input_hash(document)
    gold_hash = _gold_hash(document)
    gates = _gates(metrics, document, input_hash, gold_hash)
    return EvalReport(
        schemaVersion="1.1",
        versions=document.versions,
        dataset=DatasetIdentity(
            inputSha256=input_hash,
            goldSha256=gold_hash,
            caseCount=len(document.cases),
        ),
        metrics=metrics,
        gates=gates,
        overallPassed=all(item.passed for item in gates),
    )


def compare_reports(current: EvalReport, baseline: EvalReport) -> ReportComparison:
    compatible = (
        current.schema_version == baseline.schema_version
        and current.dataset.gold_sha256 == baseline.dataset.gold_sha256
    )
    current_metrics = current.metrics
    baseline_metrics = baseline.metrics
    return ReportComparison(
        compatible=compatible,
        metricDeltas={
            "retrievalRecallAt5": _rounded(
                current_metrics.retrieval_recall_at_5.value
                - baseline_metrics.retrieval_recall_at_5.value
            ),
            "ndcgAt3": _rounded(current_metrics.ndcg_at_3 - baseline_metrics.ndcg_at_3),
            "evidenceIdPrecision": _rounded(
                current_metrics.evidence_id_precision.value
                - baseline_metrics.evidence_id_precision.value
            ),
            "claimSupport": _rounded(
                current_metrics.claim_support.value - baseline_metrics.claim_support.value
            ),
            "safety": _rounded(current_metrics.safety.value - baseline_metrics.safety.value),
            "latencyP95Ms": (current_metrics.latency_p95_ms - baseline_metrics.latency_p95_ms),
            "meanCostMicros": (
                current_metrics.mean_cost_micros - baseline_metrics.mean_cost_micros
            ),
            "maxCostMicros": (current_metrics.max_cost_micros - baseline_metrics.max_cost_micros),
        },
    )


def _ndcg(retrieved_refs: tuple[str, ...], grades: Mapping[str, int]) -> float:
    unique_refs = _unique_refs(retrieved_refs)[:3]
    actual = _discounted_gain(tuple(grades.get(ref, 0) for ref in unique_refs))
    ideal = _discounted_gain(tuple(sorted(grades.values(), reverse=True)[:3]))
    return actual / ideal if ideal else 1.0


def _discounted_gain(grades: tuple[int, ...]) -> float:
    return math.fsum((2**grade - 1) / math.log2(index + 2) for index, grade in enumerate(grades))


def _safety_violations(case: EvaluationCase) -> int:
    gold = case.gold
    actual = case.actual
    allowed = set(gold.allowed_refs)
    excluded = set(gold.excluded_refs)
    violations = 0

    violations += actual.outcome != gold.expected_outcome
    violations += len(actual.retrieved_refs) != len(set(actual.retrieved_refs))
    violations += sum(ref not in allowed or ref in excluded for ref in actual.retrieved_refs)
    violations += len(actual.ordered_refs) != len(set(actual.ordered_refs))
    violations += len(actual.ordered_refs) > 3
    violations += sum(ref not in allowed or ref in excluded for ref in actual.ordered_refs)

    reason_refs = tuple(reason.ref for reason in actual.reasons)
    if actual.outcome is ProposalOutcome.PROPOSE_BOARD:
        violations += not set(gold.pinned_refs).issubset(actual.ordered_refs)
        violations += reason_refs != actual.ordered_refs
    else:
        violations += bool(actual.ordered_refs)
        violations += bool(actual.reasons)

    for reason in actual.reasons:
        expected = set(gold.supported_evidence.get(reason.ref, ()))
        violations += not reason.evidence_ids
        violations += len(reason.evidence_ids) != len(set(reason.evidence_ids))
        violations += sum(evidence_id not in expected for evidence_id in reason.evidence_ids)
    return violations


def _gates(
    metrics: EvalMetrics,
    document: EvalDocument,
    input_hash: str,
    gold_hash: str,
) -> tuple[GateResult, ...]:
    threshold = document.thresholds
    return (
        GateResult(
            name="quality",
            passed=(
                metrics.retrieval_recall_at_5.value >= threshold.min_retrieval_recall_at_5
                and metrics.ndcg_at_3 >= threshold.min_ndcg_at_3
                and metrics.claim_support.value >= threshold.min_claim_support
            ),
            observed={
                "retrievalRecallAt5": metrics.retrieval_recall_at_5.value,
                "ndcgAt3": metrics.ndcg_at_3,
                "claimSupport": metrics.claim_support.value,
            },
            thresholds={
                "minRetrievalRecallAt5": threshold.min_retrieval_recall_at_5,
                "minNdcgAt3": threshold.min_ndcg_at_3,
                "minClaimSupport": threshold.min_claim_support,
            },
        ),
        GateResult(
            name="safety",
            passed=(
                metrics.safety.value >= threshold.min_safety
                and metrics.safety.violations == 0
                and metrics.evidence_id_precision.value == 1.0
            ),
            observed={
                "safety": metrics.safety.value,
                "violations": metrics.safety.violations,
                "evidenceIdPrecision": metrics.evidence_id_precision.value,
            },
            thresholds={
                "minSafety": threshold.min_safety,
                "maxViolations": 0,
                "minEvidenceIdPrecision": 1.0,
            },
        ),
        GateResult(
            name="latency",
            passed=metrics.latency_p95_ms <= threshold.max_latency_p95_ms,
            observed={"latencyP95Ms": metrics.latency_p95_ms},
            thresholds={"maxLatencyP95Ms": threshold.max_latency_p95_ms},
        ),
        GateResult(
            name="cost",
            passed=(
                metrics.mean_cost_micros <= threshold.max_mean_cost_micros
                and metrics.max_cost_micros <= threshold.max_per_run_cost_micros
            ),
            observed={
                "meanCostMicros": metrics.mean_cost_micros,
                "maxCostMicros": metrics.max_cost_micros,
            },
            thresholds={
                "maxMeanCostMicros": threshold.max_mean_cost_micros,
                "maxPerRunCostMicros": threshold.max_per_run_cost_micros,
            },
        ),
        GateResult(
            name="determinism",
            passed=len(input_hash) == 64 and len(gold_hash) == 64,
            observed={"canonicalFingerprints": 2},
            thresholds={"requiredCanonicalFingerprints": 2},
        ),
    )


def _input_hash(document: EvalDocument) -> str:
    return _canonical_hash(document.model_dump(mode="json", by_alias=True))


def _gold_hash(document: EvalDocument) -> str:
    return _canonical_hash(
        {
            "cases": [
                {
                    "id": case.id,
                    "gold": case.gold.model_dump(mode="json", by_alias=True),
                }
                for case in document.cases
            ],
        }
    )


def _canonical_hash(value: object) -> str:
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode()
    return hashlib.sha256(canonical).hexdigest()


def _unique_refs(refs: tuple[str, ...]) -> tuple[str, ...]:
    return tuple(dict.fromkeys(refs))


def _nearest_rank_percentile(values: list[int], percentile: float) -> int:
    ordered = sorted(values)
    return ordered[max(0, math.ceil(percentile * len(ordered)) - 1)]


def _ratio(numerator: int, denominator: int) -> float:
    return numerator / denominator if denominator else 1.0


def _rounded(value: float) -> float:
    return round(value, 6)
