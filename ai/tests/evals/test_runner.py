from __future__ import annotations

import json
from copy import deepcopy
from pathlib import Path
from typing import Any, cast

import pytest
from jsonschema import Draft202012Validator
from pydantic import ValidationError

from onmaru_ai.evals import EvalReport, compare_reports, evaluate_document, load_eval_document
from onmaru_ai.evals.cli import main
from onmaru_ai.evals.runner import _ndcg

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
    assert len(report.dataset.gold_sha256) == 64
    assert report.metrics.retrieval_recall_at_5.model_dump(by_alias=True) == {
        "numerator": 3,
        "denominator": 3,
        "value": 1.0,
    }
    assert report.metrics.ndcg_at_3 == 0.815465
    assert report.metrics.evidence_id_precision.model_dump(by_alias=True) == {
        "numerator": 3,
        "denominator": 3,
        "value": 1.0,
    }
    assert report.metrics.claim_support.model_dump(by_alias=True) == {
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
        "safety": True,
        "latency": True,
        "cost": True,
        "determinism": True,
    }
    assert report.overall_passed is True


def test_fails_closed_for_unsupported_evidence_constraint_latency_and_cost() -> None:
    document = fixture_document()
    unsafe = document["cases"][0]
    unsafe["actual"]["orderedRefs"] = ["place-outside"]
    unsafe["actual"]["reasons"] = [
        {
            "ref": "place-outside",
            "evidenceIds": ["fabricated-evidence"],
            "summary": "검수되지 않은 주장입니다.",
            "humanClaimSupported": False,
        }
    ]
    unsafe["actual"]["latencyMs"] = 20_001
    unsafe["actual"]["costMicros"] = 2_001

    report = evaluate_document(document)

    assert report.metrics.evidence_id_precision.value < 1.0
    assert report.metrics.safety.violations >= 2
    assert report.overall_passed is False
    assert {gate.name for gate in report.gates if not gate.passed} == {
        "quality",
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


def test_rejects_empty_or_mismatched_proposal_shape() -> None:
    empty_board = fixture_document()
    empty_board["cases"][0]["actual"].update(orderedRefs=[], reasons=[])
    missing_reason = fixture_document()
    missing_reason["cases"][0]["actual"]["reasons"].pop()
    wrong_outcome_shape = fixture_document()
    wrong_outcome_shape["cases"][0]["actual"]["outcome"] = "NO_RESULTS"

    for document in (empty_board, missing_reason, wrong_outcome_shape):
        try:
            load_eval_document(document)
        except ValueError:
            pass
        else:
            raise AssertionError("proposal shape must match the #105 outcome contract")


def test_rejects_duplicate_retrieval_refs_without_scoring_or_cli_traceback(
    tmp_path: Path,
) -> None:
    document = fixture_document()
    document["cases"][0]["actual"]["retrievedRefs"] = ["place-a", "place-a"]
    input_path = tmp_path / "duplicate.json"
    output_path = tmp_path / "report.json"
    input_path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")

    assert main(["--input", str(input_path), "--output", str(output_path)]) == 2
    assert not output_path.exists()
    assert _ndcg(("place-a", "place-a", "place-b"), {"place-a": 2, "place-b": 1}) <= 1


def test_human_claim_support_is_distinct_from_evidence_id_precision() -> None:
    document = fixture_document()
    document["cases"][0]["actual"]["reasons"][0]["humanClaimSupported"] = False

    report = evaluate_document(document)

    assert report.metrics.evidence_id_precision.value == 1.0
    assert report.metrics.claim_support.value == 0.666667
    assert {gate.name for gate in report.gates if not gate.passed} == {"quality"}


@pytest.mark.parametrize(
    "unsafe_summary",
    [
        "   ",
        "https://example.invalid/path",
        "custom+scheme:value",
        "//192.0.2.1/path",
        "예시.한국/경로",
        "**강조**",
        "제목\n---",
    ],
)
def test_rejects_summary_that_production_proposal_validator_rejects(
    unsafe_summary: str,
) -> None:
    document = fixture_document()
    document["cases"][0]["actual"]["reasons"][0]["summary"] = unsafe_summary

    with pytest.raises(ValueError, match="invalid evaluation document"):
        load_eval_document(document)


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
        "evidenceIdPrecision": 0.0,
        "claimSupport": 0.0,
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


def test_comparison_uses_gold_fingerprint_not_model_actual() -> None:
    baseline_document = fixture_document()
    changed_actual = fixture_document()
    changed_actual["versions"]["model"] = "challenger-model-v2"
    changed_actual["cases"][0]["actual"]["latencyMs"] = 7999
    changed_actual["cases"][0]["actual"]["reasons"][0]["summary"] = (
        "challenger가 생성한 다른 설명입니다."
    )
    changed_actual["cases"][0]["actual"]["reasons"][0]["humanClaimSupported"] = False

    baseline = evaluate_document(baseline_document)
    challenger = evaluate_document(changed_actual)

    assert baseline.dataset.gold_sha256 == challenger.dataset.gold_sha256
    assert compare_reports(challenger, baseline).compatible is True

    changed_gold = fixture_document()
    changed_gold["cases"][0]["gold"]["relevance"][0]["grade"] = 1
    changed_gold_report = evaluate_document(changed_gold)

    assert changed_gold_report.dataset.gold_sha256 != baseline.dataset.gold_sha256
    assert compare_reports(changed_gold_report, baseline).compatible is False


def test_dataset_display_name_does_not_change_gold_fingerprint_or_compatibility() -> None:
    baseline = evaluate_document(fixture_document())
    renamed = fixture_document()
    renamed["versions"]["dataset"] = "journey-held-out-renamed"

    renamed_report = evaluate_document(renamed)

    assert renamed_report.dataset.gold_sha256 == baseline.dataset.gold_sha256
    assert compare_reports(renamed_report, baseline).compatible is True


def test_report_rejects_missing_duplicate_or_inconsistent_gate_sets() -> None:
    payload = evaluate_document(fixture_document()).model_dump(mode="json", by_alias=True)
    schema_validator = Draft202012Validator(
        EvalReport.model_json_schema(by_alias=True)
    )

    empty = deepcopy(payload)
    empty["gates"] = []
    missing_gate = deepcopy(payload)
    missing_gate["gates"] = missing_gate["gates"][:-1]
    missing_field = deepcopy(payload)
    del missing_field["gates"]
    duplicate = deepcopy(payload)
    duplicate["gates"][-1] = deepcopy(duplicate["gates"][0])
    wrong_overall = deepcopy(payload)
    wrong_overall["overallPassed"] = False

    for invalid in (empty, missing_gate, missing_field, duplicate, wrong_overall):
        with pytest.raises(ValidationError):
            EvalReport.model_validate(invalid)
        assert list(schema_validator.iter_errors(invalid))


def test_snake_case_wire_keys_are_rejected_with_exit_two(tmp_path: Path) -> None:
    document = fixture_document()
    actual = document["cases"][0]["actual"]
    actual["ordered_refs"] = actual.pop("orderedRefs")
    input_path = tmp_path / "snake-case.json"
    output_path = tmp_path / "report.json"
    input_path.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")

    assert main(["--input", str(input_path), "--output", str(output_path)]) == 2
    assert not output_path.exists()


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
    gates_schema = checked_in["properties"]["gates"]
    assert gates_schema["minItems"] == 5
    assert gates_schema["maxItems"] == 5
    assert gates_schema["uniqueItems"] is True
    assert len(checked_in["allOf"]) == 5
    assert len(checked_in["oneOf"]) == 2
