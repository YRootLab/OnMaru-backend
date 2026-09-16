from __future__ import annotations

import re
from dataclasses import dataclass, field
from enum import StrEnum
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field

MAX_CANDIDATES = 12
MAX_BOARD_REFS = 3
MAX_EVIDENCE_PER_REASON = 3
MAX_SUMMARY_CODE_POINTS = 200
MAX_QUESTION_CODE_POINTS = 200
MAX_CHOICES = 5

IDENTIFIER_PATTERN = r"^[A-Za-z0-9][A-Za-z0-9._:-]*$"
_IDENTIFIER_RE = re.compile(IDENTIFIER_PATTERN)

Identifier = Annotated[
    str,
    Field(min_length=1, max_length=128, pattern=IDENTIFIER_PATTERN),
]


class ProposalOutcome(StrEnum):
    PROPOSE_BOARD = "PROPOSE_BOARD"
    ASK_CLARIFICATION = "ASK_CLARIFICATION"
    NO_RESULTS = "NO_RESULTS"


class ClarificationReason(StrEnum):
    UNSUPPORTED_CONDITION = "UNSUPPORTED_CONDITION"


class ProposalDegradedReason(StrEnum):
    AI_INVALID_RESPONSE = "AI_INVALID_RESPONSE"


class ProposalRejectionCode(StrEnum):
    MALFORMED_OUTPUT = "MALFORMED_OUTPUT"
    UNEXPECTED_FIELD = "UNEXPECTED_FIELD"
    TOO_MANY_ITEMS = "TOO_MANY_ITEMS"
    TEXT_TOO_LONG = "TEXT_TOO_LONG"
    UNSAFE_TEXT = "UNSAFE_TEXT"
    DUPLICATE_REF = "DUPLICATE_REF"
    UNKNOWN_REF = "UNKNOWN_REF"
    EXCLUDED_REF = "EXCLUDED_REF"
    MISSING_PIN = "MISSING_PIN"
    DUPLICATE_REASON = "DUPLICATE_REASON"
    MISSING_REASON = "MISSING_REASON"
    REASON_ORDER_MISMATCH = "REASON_ORDER_MISMATCH"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"
    EVIDENCE_OWNERSHIP_MISMATCH = "EVIDENCE_OWNERSHIP_MISMATCH"


@dataclass(frozen=True)
class AllowedEvidence:
    id: str
    revision_id: str

    def __post_init__(self) -> None:
        if len(self.id) > 128 or _IDENTIFIER_RE.fullmatch(self.id) is None:
            raise ValueError("evidence id format is invalid")
        if not self.revision_id.strip():
            raise ValueError("evidence revision must not be blank")


@dataclass(frozen=True)
class AllowedCandidate:
    ref: str
    evidence: tuple[AllowedEvidence, ...]

    def __post_init__(self) -> None:
        if len(self.ref) > 128 or _IDENTIFIER_RE.fullmatch(self.ref) is None:
            raise ValueError("candidate ref format is invalid")
        if not 1 <= len(self.evidence) <= MAX_EVIDENCE_PER_REASON:
            raise ValueError("candidate must contain one to three evidence items")
        evidence_ids = tuple(item.id for item in self.evidence)
        if len(set(evidence_ids)) != len(evidence_ids):
            raise ValueError("candidate evidence ids must be unique")


@dataclass(frozen=True)
class ProposalScope:
    candidate_revision: str
    candidates: tuple[AllowedCandidate, ...]
    pinned_refs: tuple[str, ...] = ()
    excluded_refs: frozenset[str] = field(default_factory=frozenset)

    def __post_init__(self) -> None:
        if not self.candidate_revision.strip():
            raise ValueError("candidate revision must not be blank")
        if not 1 <= len(self.candidates) <= MAX_CANDIDATES:
            raise ValueError("scope must contain one to twelve candidates")
        candidate_refs = tuple(candidate.ref for candidate in self.candidates)
        if len(set(candidate_refs)) != len(candidate_refs):
            raise ValueError("candidate refs must be unique")
        if len(self.pinned_refs) > MAX_BOARD_REFS or len(set(self.pinned_refs)) != len(
            self.pinned_refs
        ):
            raise ValueError("pinned refs must contain at most three unique refs")
        if not set(self.pinned_refs).issubset(candidate_refs):
            raise ValueError("pinned refs must belong to candidate scope")
        if set(candidate_refs).intersection(self.excluded_refs):
            raise ValueError("excluded refs must not appear in candidate scope")

        evidence_ids: set[str] = set()
        for candidate in self.candidates:
            for evidence in candidate.evidence:
                if evidence.revision_id != self.candidate_revision:
                    raise ValueError("evidence revision must match candidate revision")
                if evidence.id in evidence_ids:
                    raise ValueError("evidence ids must be unique across candidate scope")
                evidence_ids.add(evidence.id)


class ProposalModel(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True, populate_by_name=True)


class ProposalReason(ProposalModel):
    ref: Identifier
    evidence_ids: Annotated[
        tuple[Identifier, ...],
        Field(alias="evidenceIds", min_length=1, max_length=MAX_EVIDENCE_PER_REASON),
    ]
    summary: Annotated[str, Field(min_length=1, max_length=MAX_SUMMARY_CODE_POINTS)]


class ClarificationChoice(ProposalModel):
    id: Identifier
    label: Annotated[str, Field(min_length=1, max_length=80)]
    region_code: Annotated[str, Field(min_length=1, max_length=32)] | None = Field(
        alias="regionCode"
    )


class ProposalClarification(ProposalModel):
    reason: Literal[ClarificationReason.UNSUPPORTED_CONDITION]
    question: Annotated[str, Field(min_length=1, max_length=MAX_QUESTION_CODE_POINTS)]
    choices: Annotated[tuple[ClarificationChoice, ...], Field(max_length=MAX_CHOICES)]
    allow_free_text: bool = Field(alias="allowFreeText")


class ProposeBoard(ProposalModel):
    outcome: Literal[ProposalOutcome.PROPOSE_BOARD]
    ordered_refs: Annotated[
        tuple[Identifier, ...],
        Field(alias="orderedRefs", min_length=1, max_length=MAX_BOARD_REFS),
    ]
    reasons: Annotated[tuple[ProposalReason, ...], Field(min_length=1, max_length=MAX_BOARD_REFS)]
    clarification: None


class AskClarification(ProposalModel):
    outcome: Literal[ProposalOutcome.ASK_CLARIFICATION]
    ordered_refs: Annotated[tuple[Identifier, ...], Field(alias="orderedRefs", max_length=0)]
    reasons: Annotated[tuple[ProposalReason, ...], Field(max_length=0)]
    clarification: ProposalClarification


class NoResults(ProposalModel):
    outcome: Literal[ProposalOutcome.NO_RESULTS]
    ordered_refs: Annotated[tuple[Identifier, ...], Field(alias="orderedRefs", max_length=0)]
    reasons: Annotated[tuple[ProposalReason, ...], Field(max_length=0)]
    clarification: None


type ProviderProposal = Annotated[
    ProposeBoard | AskClarification | NoResults,
    Field(discriminator="outcome"),
]
