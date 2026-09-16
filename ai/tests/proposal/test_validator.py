from __future__ import annotations

import json
from copy import deepcopy
from typing import Any

import pytest

from onmaru_ai.proposal import (
    AllowedCandidate,
    AllowedEvidence,
    ProposalDegradedReason,
    ProposalRejectionCode,
    ProposalScope,
    ProposalValidationError,
    ProposalValidator,
    proposal_response_schema,
)


def scope(*, pinned_refs: tuple[str, ...] = ("place-a",)) -> ProposalScope:
    return ProposalScope(
        candidate_revision="revision-42",
        candidates=(
            AllowedCandidate(
                ref="place-a",
                evidence=(AllowedEvidence(id="evidence-a", revision_id="revision-42"),),
            ),
            AllowedCandidate(
                ref="place-b",
                evidence=(AllowedEvidence(id="evidence-b", revision_id="revision-42"),),
            ),
            AllowedCandidate(
                ref="place-c",
                evidence=(AllowedEvidence(id="evidence-c", revision_id="revision-42"),),
            ),
        ),
        pinned_refs=pinned_refs,
        excluded_refs=frozenset({"place-excluded"}),
    )


def board_payload() -> dict[str, Any]:
    return {
        "outcome": "PROPOSE_BOARD",
        "orderedRefs": ["place-a", "place-b"],
        "reasons": [
            {
                "ref": "place-a",
                "evidenceIds": ["evidence-a"],
                "summary": "검수된 첫 번째 근거를 바탕으로 시작합니다.",
            },
            {
                "ref": "place-b",
                "evidenceIds": ["evidence-b"],
                "summary": "검수된 두 번째 근거로 여정을 이어갑니다.",
            },
        ],
        "clarification": None,
    }


def rejection(payload: object, expected: ProposalRejectionCode) -> ProposalValidationError:
    with pytest.raises(ProposalValidationError) as captured:
        ProposalValidator().validate(payload, scope())
    assert captured.value.code is expected
    assert captured.value.degraded_reason is ProposalDegradedReason.AI_INVALID_RESPONSE
    assert "place-secret" not in str(captured.value)
    return captured.value


def test_accepts_board_when_refs_reasons_and_evidence_are_in_scope() -> None:
    proposal = ProposalValidator().validate(board_payload(), scope())

    assert proposal.outcome == "PROPOSE_BOARD"
    assert proposal.ordered_refs == ("place-a", "place-b")
    assert tuple(reason.ref for reason in proposal.reasons) == proposal.ordered_refs


def test_accepts_typed_clarification_and_no_results_without_board_data() -> None:
    clarification = ProposalValidator().validate(
        {
            "outcome": "ASK_CLARIFICATION",
            "orderedRefs": [],
            "reasons": [],
            "clarification": {
                "reason": "UNSUPPORTED_CONDITION",
                "question": "실시간 혼잡도 대신 조용한 분위기를 기준으로 찾을까요?",
                "choices": [
                    {"id": "quiet", "label": "조용한 분위기", "regionCode": None}
                ],
                "allowFreeText": False,
            },
        },
        scope(),
    )
    no_results = ProposalValidator().validate(
        {
            "outcome": "NO_RESULTS",
            "orderedRefs": [],
            "reasons": [],
            "clarification": None,
        },
        scope(),
    )

    assert clarification.outcome == "ASK_CLARIFICATION"
    assert clarification.clarification is not None
    assert no_results.outcome == "NO_RESULTS"


@pytest.mark.parametrize(
    ("mutate", "expected"),
    [
        (
            lambda payload: payload["orderedRefs"].append("place-a"),
            ProposalRejectionCode.DUPLICATE_REF,
        ),
        (
            lambda payload: payload["orderedRefs"].append("place-secret"),
            ProposalRejectionCode.UNKNOWN_REF,
        ),
        (
            lambda payload: payload["orderedRefs"].append("place-excluded"),
            ProposalRejectionCode.EXCLUDED_REF,
        ),
        (
            lambda payload: payload["reasons"].append(deepcopy(payload["reasons"][0])),
            ProposalRejectionCode.DUPLICATE_REASON,
        ),
        (
            lambda payload: payload["reasons"].pop(),
            ProposalRejectionCode.MISSING_REASON,
        ),
        (
            lambda payload: payload["reasons"][0]["evidenceIds"].append("evidence-b"),
            ProposalRejectionCode.EVIDENCE_OWNERSHIP_MISMATCH,
        ),
        (
            lambda payload: payload["reasons"][0].update(evidenceIds=[]),
            ProposalRejectionCode.MISSING_EVIDENCE,
        ),
        (
            lambda payload: payload["reasons"][0].update(summary="가" * 201),
            ProposalRejectionCode.TEXT_TOO_LONG,
        ),
        (
            lambda payload: payload["reasons"][0].update(
                summary="자세한 내용은 https://example.invalid 에 있습니다."
            ),
            ProposalRejectionCode.UNSAFE_TEXT,
        ),
        (
            lambda payload: payload.update(toolCall={"name": "search"}),
            ProposalRejectionCode.UNEXPECTED_FIELD,
        ),
    ],
)
def test_rejects_adversarial_board_variants_with_typed_codes(
    mutate: Any,
    expected: ProposalRejectionCode,
) -> None:
    payload = board_payload()
    mutate(payload)

    rejection(payload, expected)


def test_rejects_missing_pin_even_when_every_returned_ref_is_known() -> None:
    payload = board_payload()
    payload["orderedRefs"] = ["place-b"]
    payload["reasons"] = [payload["reasons"][1]]

    rejection(payload, ProposalRejectionCode.MISSING_PIN)


@pytest.mark.parametrize(
    "unsafe_summary",
    [
        "<script",
        "**검수되지 않은 강조**",
        "example.com/path에서 확인하세요.",
        "제어문자\x00포함",
    ],
)
def test_rejects_markup_url_and_control_character_variants(unsafe_summary: str) -> None:
    payload = board_payload()
    payload["reasons"][0]["summary"] = unsafe_summary

    rejection(payload, ProposalRejectionCode.UNSAFE_TEXT)


def test_clarification_cannot_introduce_a_region_reference() -> None:
    payload = {
        "outcome": "ASK_CLARIFICATION",
        "orderedRefs": [],
        "reasons": [],
        "clarification": {
            "reason": "UNSUPPORTED_CONDITION",
            "question": "다른 조건을 선택할까요?",
            "choices": [{"id": "other", "label": "다른 조건", "regionCode": "11"}],
            "allowFreeText": False,
        },
    }

    rejection(payload, ProposalRejectionCode.UNKNOWN_REF)


def test_rejects_every_generated_unknown_ref_without_echoing_it() -> None:
    validator = ProposalValidator()
    for index in range(100):
        unknown_ref = f"place-secret-{index}"
        payload = board_payload()
        payload["orderedRefs"] = ["place-a", unknown_ref]
        payload["reasons"][1]["ref"] = unknown_ref
        with pytest.raises(ProposalValidationError) as captured:
            validator.validate(payload, scope())
        assert captured.value.code is ProposalRejectionCode.UNKNOWN_REF
        assert unknown_ref not in str(captured.value)


def test_rejects_overlong_board_before_allowlist_processing() -> None:
    payload = board_payload()
    payload["orderedRefs"] = ["place-a", "place-b", "place-c", "place-secret"]
    payload["reasons"] = [payload["reasons"][0]] * 4

    rejection(payload, ProposalRejectionCode.TOO_MANY_ITEMS)


@pytest.mark.parametrize(
    "payload",
    [
        None,
        [],
        {"outcome": "PROPOSE_BOARD"},
        {
            "outcome": "ASK_CLARIFICATION",
            "orderedRefs": ["place-a"],
            "reasons": [],
            "clarification": None,
        },
        {
            "outcome": "NO_RESULTS",
            "orderedRefs": [],
            "reasons": [],
            "clarification": {"reason": "UNSUPPORTED_CONDITION"},
        },
    ],
)
def test_rejects_malformed_or_cross_outcome_shapes(payload: object) -> None:
    rejection(payload, ProposalRejectionCode.MALFORMED_OUTPUT)


def test_scope_rejects_ambiguous_or_stale_evidence_before_provider_validation() -> None:
    with pytest.raises(ValueError, match="candidate refs must be unique"):
        ProposalScope(
            candidate_revision="revision-42",
            candidates=(scope().candidates[0], scope().candidates[0]),
        )
    with pytest.raises(ValueError, match="evidence revision"):
        ProposalScope(
            candidate_revision="revision-42",
            candidates=(
                AllowedCandidate(
                    ref="place-a",
                    evidence=(AllowedEvidence(id="evidence-a", revision_id="stale"),),
                ),
            ),
        )
    with pytest.raises(ValueError, match="candidate ref format"):
        ProposalScope(
            candidate_revision="revision-42",
            candidates=(
                AllowedCandidate(
                    ref="place/a",
                    evidence=(AllowedEvidence(id="evidence-a", revision_id="revision-42"),),
                ),
            ),
        )


def test_response_schema_is_closed_and_keeps_all_outcomes_bounded() -> None:
    schema = proposal_response_schema()
    serialized = json.dumps(schema, sort_keys=True)

    assert len(schema["oneOf"]) == 3
    assert '"$defs"' not in serialized
    assert '"$ref"' not in serialized
    assert '"discriminator"' not in serialized
    assert all(branch["additionalProperties"] is False for branch in schema["oneOf"])
    board = next(
        branch
        for branch in schema["oneOf"]
        if branch["properties"]["outcome"]["const"] == "PROPOSE_BOARD"
    )
    assert board["properties"]["orderedRefs"]["maxItems"] == 3
    assert board["properties"]["reasons"]["maxItems"] == 3
