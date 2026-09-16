from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any, cast

from onmaru_ai.evals import EvalReport, compare_reports, evaluate_document, load_eval_document
from onmaru_ai.evals.cli import main

EVAL_ROOT = Path(__file__).parents[2] / "evals"
FIXTURE_PATH = EVAL_ROOT / "journey-held-out-v1.json"
SCHEMA_PATH = EVAL_ROOT / "report.schema.json"


def fixture_document() -> dict[str, Any]:
    return cast(dict[str, Any], json.loads(FIXTURE_PATH.read_text(encoding="utf-8")))


def test_scores_fixed_versions_quality_safety_latency_and_cost() -> None:
    report = evaluate_document(fixture_document())

    assert report.versions.model == "offline-proposal-fixture-v1"
    assert report.versions.prompt == "journey-proposal-v1"
    assert report.versions.ranking == "baseline-ranking-v1"
    assert report.versions.dataset == "journey-held-out-v1"
    assert report.dataset.case_count == 4
    assert len(report.dataset.input_sha256) == 64
    assert report.metrics.retrieval_recall_at_5.model_dump(by_alias=True) == {
        "numerator": 3,
        "denominator": 3,
        "value": 1.0,
    }
    assert report.metrics.ndcg_at_3 == 0.815465
    assert report.metrics.evidence_faithfulness.model_dump(by_alias=True) == {
        "numerator": 3,
        "denominator": 3,
        "value": 1.0,
    }
    assert report.metrics.safety.model_dump(by_alias=True) == {
        "passingCases": 4,
        "totalCases": 4,
        "violations": 0,
        "value": 1.0,
    }
    assert report.metrics.latency_p95_ms == 7000
    assert report.metrics.mean_cost_micros == 250
    assert report.metrics.max_cost_micros == 400
    assert {gate.name: gate.passed for gate in report.gates} == {
        "quality": True,
        "evidenceFaithfulness": True,
        "safety": True,
        "latency": True,
        "cost": True,
    }
    assert report.overall_passed is True


def test_fails_closed_for_unsupported_evidence_constraint_latency_and_cost() -> None:
    document = fixture_document()
    unsafe = document["cases"][0]
    unsafe["actual"]["orderedRefs"] = ["place-outside"]
    unsafe["actual"]["reasons"] = [
        {"ref": "place-outside", "evidenceIds": ["fabricated-evidence"]}
    ]
    unsafe["actual"]["latencyMs"] = 20_001
    unsafe["actual"]["costMicros"] = 2_001

    report = evaluate_document(document)

    assert report.metrics.evidence_faithfulness.value < 1.0
    assert report.metrics.safety.violations >= 2
    assert report.overall_passed is False
    assert {gate.name for gate in report.gates if not gate.passed} == {
        "evidenceFaithfulness",
        "safety",
        "latency",
        "cost",
    }


def test_empty_retrieval_scores_zero_when_relevant_gold_exists() -> None:
    document = fixture_document()
    document["cases"][0]["actual"]["retrievedRefs"] = []

    report = evaluate_document(document)

    assert report.metrics.retrieval_recall_at_5.value == 0.333333
    assert report.metrics.ndcg_at_3 == 0.315465


def test_report_is_byte_reproducible_and_comparable(tmp_path: Path) -> None:
    first_path = tmp_path / "first.json"
    second_path = tmp_path / "second.json"

    assert main(["--input", str(FIXTURE_PATH), "--output", str(first_path)]) == 0
    assert main(["--input", str(FIXTURE_PATH), "--output", str(second_path)]) == 0
    assert first_path.read_bytes() == second_path.read_bytes()

    first = EvalReport.model_validate_json(first_path.read_text(encoding="utf-8"))
    second = EvalReport.model_validate_json(second_path.read_text(encoding="utf-8"))
    comparison = compare_reports(second, first)

    assert comparison.compatible is True
    assert comparison.metric_deltas == {
        "retrievalRecallAt5": 0.0,
        "ndcgAt3": 0.0,
        "evidenceFaithfulness": 0.0,
        "safety": 0.0,
        "latencyP95Ms": 0,
        "meanCostMicros": 0,
        "maxCostMicros": 0,
    }

    compared_path = tmp_path / "compared.json"
    assert (
        main(
            [
                "--input",
                str(FIXTURE_PATH),
                "--output",
                str(compared_path),
                "--compare",
                str(first_path),
            ]
        )
        == 0
    )
    compared = EvalReport.model_validate_json(compared_path.read_text(encoding="utf-8"))
    assert compared.comparison is not None
    assert compared.comparison.compatible is True


def test_failed_gate_returns_nonzero_and_writes_report(tmp_path: Path) -> None:
    document = fixture_document()
    document["thresholds"]["minNdcgAt3"] = 1.0
    input_path = tmp_path / "failing.json"
    output_path = tmp_path / "report.json"
    input_path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")

    exit_code = main(["--input", str(input_path), "--output", str(output_path)])

    assert exit_code == 1
    failed_report = EvalReport.model_validate_json(output_path.read_text(encoding="utf-8"))
    assert failed_report.overall_passed is False


def test_rejects_duplicate_case_ids_and_incomplete_version_pins() -> None:
    duplicate = fixture_document()
    duplicate["cases"].append(deepcopy(duplicate["cases"][0]))

    invalid_gold = fixture_document()
    invalid_gold["cases"][0]["gold"]["pinnedRefs"] = ["not-allowed"]

    for invalid in (
        duplicate,
        {**fixture_document(), "versions": {"model": "only-model"}},
        invalid_gold,
    ):
        try:
            load_eval_document(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError("invalid evaluation document must be rejected")


def test_checked_in_report_schema_matches_runtime_contract() -> None:
    checked_in = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))

    assert checked_in == EvalReport.model_json_schema(by_alias=True)
