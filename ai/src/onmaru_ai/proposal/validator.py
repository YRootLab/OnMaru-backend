from __future__ import annotations

import re
from typing import Any

from pydantic import TypeAdapter, ValidationError

from .models import (
    AskClarification,
    NoResults,
    ProposalDegradedReason,
    ProposalRejectionCode,
    ProposalScope,
    ProposeBoard,
    ProviderProposal,
)

_PROPOSAL_ADAPTER: TypeAdapter[ProviderProposal] = TypeAdapter(ProviderProposal)
_UNSAFE_TEXT = re.compile(
    r"(?:https?|ftp|mailto|data|file|javascript):|www\.|"
    r"(?:[a-z0-9-]+\.)+[a-z]{2,63}(?:[/:?#]|\b)|"
    r"[\[\]<>`#*_]|~~|"
    r"(?:^|\n)\s{0,3}(?:[-+]\s|\d+[.)]\s)|"
    r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]",
    re.IGNORECASE,
)


class ProposalValidationError(ValueError):
    def __init__(self, code: ProposalRejectionCode) -> None:
        self.code = code
        self.degraded_reason = ProposalDegradedReason.AI_INVALID_RESPONSE
        super().__init__(f"{code.value}: provider proposal rejected")


class ProposalValidator:
    def validate(self, payload: object, scope: ProposalScope) -> ProviderProposal:
        try:
            proposal = _PROPOSAL_ADAPTER.validate_python(payload)
        except ValidationError as error:
            raise ProposalValidationError(self._schema_rejection(error)) from None

        if isinstance(proposal, ProposeBoard):
            self._validate_board(proposal, scope)
        elif isinstance(proposal, AskClarification):
            self._validate_clarification(proposal)
        elif not isinstance(proposal, NoResults):
            raise ProposalValidationError(ProposalRejectionCode.MALFORMED_OUTPUT)
        return proposal

    def _validate_board(self, proposal: ProposeBoard, scope: ProposalScope) -> None:
        ordered_refs = proposal.ordered_refs
        if len(set(ordered_refs)) != len(ordered_refs):
            raise ProposalValidationError(ProposalRejectionCode.DUPLICATE_REF)

        excluded = set(ordered_refs).intersection(scope.excluded_refs)
        if excluded:
            raise ProposalValidationError(ProposalRejectionCode.EXCLUDED_REF)

        candidates = {candidate.ref: candidate for candidate in scope.candidates}
        if any(ref not in candidates for ref in ordered_refs):
            raise ProposalValidationError(ProposalRejectionCode.UNKNOWN_REF)
        if not set(scope.pinned_refs).issubset(ordered_refs):
            raise ProposalValidationError(ProposalRejectionCode.MISSING_PIN)

        reason_refs = tuple(reason.ref for reason in proposal.reasons)
        if len(set(reason_refs)) != len(reason_refs):
            raise ProposalValidationError(ProposalRejectionCode.DUPLICATE_REASON)
        if any(ref not in candidates for ref in reason_refs):
            raise ProposalValidationError(ProposalRejectionCode.UNKNOWN_REF)
        if set(reason_refs) != set(ordered_refs):
            raise ProposalValidationError(ProposalRejectionCode.MISSING_REASON)
        if reason_refs != ordered_refs:
            raise ProposalValidationError(ProposalRejectionCode.REASON_ORDER_MISMATCH)

        for reason in proposal.reasons:
            if not reason.summary.strip():
                raise ProposalValidationError(ProposalRejectionCode.MALFORMED_OUTPUT)
            self._reject_unsafe_text(reason.summary)
            allowed_evidence = {item.id for item in candidates[reason.ref].evidence}
            if len(set(reason.evidence_ids)) != len(reason.evidence_ids):
                raise ProposalValidationError(
                    ProposalRejectionCode.EVIDENCE_OWNERSHIP_MISMATCH
                )
            if not set(reason.evidence_ids).issubset(allowed_evidence):
                raise ProposalValidationError(
                    ProposalRejectionCode.EVIDENCE_OWNERSHIP_MISMATCH
                )

    def _validate_clarification(self, proposal: AskClarification) -> None:
        if not proposal.clarification.question.strip():
            raise ProposalValidationError(ProposalRejectionCode.MALFORMED_OUTPUT)
        self._reject_unsafe_text(proposal.clarification.question)
        choice_ids = tuple(choice.id for choice in proposal.clarification.choices)
        if len(set(choice_ids)) != len(choice_ids):
            raise ProposalValidationError(ProposalRejectionCode.MALFORMED_OUTPUT)
        for choice in proposal.clarification.choices:
            if choice.region_code is not None:
                raise ProposalValidationError(ProposalRejectionCode.UNKNOWN_REF)
            if not choice.label.strip():
                raise ProposalValidationError(ProposalRejectionCode.MALFORMED_OUTPUT)
            self._reject_unsafe_text(choice.label)

    @staticmethod
    def _reject_unsafe_text(value: str) -> None:
        if _UNSAFE_TEXT.search(value):
            raise ProposalValidationError(ProposalRejectionCode.UNSAFE_TEXT)

    @staticmethod
    def _schema_rejection(error: ValidationError) -> ProposalRejectionCode:
        error_types = {item["type"] for item in error.errors()}
        if "extra_forbidden" in error_types:
            return ProposalRejectionCode.UNEXPECTED_FIELD
        for item in error.errors():
            if item["type"] in {"too_long", "list_too_long", "tuple_too_long"}:
                if item.get("ctx", {}).get("max_length") == 0:
                    return ProposalRejectionCode.MALFORMED_OUTPUT
                return ProposalRejectionCode.TOO_MANY_ITEMS
        if "string_too_long" in error_types:
            return ProposalRejectionCode.TEXT_TOO_LONG

        for item in error.errors():
            if item["type"] in {"too_short", "list_too_short", "tuple_too_short"} and any(
                part == "evidenceIds" for part in item["loc"]
            ):
                return ProposalRejectionCode.MISSING_EVIDENCE
        return ProposalRejectionCode.MALFORMED_OUTPUT


def proposal_response_schema() -> dict[str, Any]:
    schema = _PROPOSAL_ADAPTER.json_schema(by_alias=True)
    definitions = schema.get("$defs", {})

    def inline(node: Any) -> Any:
        if isinstance(node, list):
            return [inline(item) for item in node]
        if not isinstance(node, dict):
            return node
        reference = node.get("$ref")
        if isinstance(reference, str):
            resolved = definitions.get(reference.rsplit("/", 1)[-1])
            if isinstance(resolved, dict):
                return inline(resolved)
        return {
            key: inline(value)
            for key, value in node.items()
            if key not in {"$defs", "discriminator"}
        }

    inlined = inline(schema)
    if not isinstance(inlined, dict):
        raise RuntimeError("proposal response schema must be an object")
    return inlined
