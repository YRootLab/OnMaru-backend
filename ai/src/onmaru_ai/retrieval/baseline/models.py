from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum
from math import isfinite


class CandidateStatus(StrEnum):
    ACTIVE = "ACTIVE"
    TOMBSTONE = "TOMBSTONE"


class HardFilterReason(StrEnum):
    REVISION_MISMATCH = "REVISION_MISMATCH"
    REGION_MISMATCH = "REGION_MISMATCH"
    EXCLUDED = "EXCLUDED"
    NOT_PUBLIC = "NOT_PUBLIC"
    TOMBSTONE = "TOMBSTONE"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"


@dataclass(frozen=True)
class Evidence:
    source_id: str
    text: str

    @property
    def has_provenance(self) -> bool:
        return bool(self.source_id.strip() and self.text.strip())


@dataclass(frozen=True)
class Candidate:
    ref: str
    revision_id: str
    region_code: str
    name: str
    category: str
    aliases: tuple[str, ...] = ()
    summary: str | None = None
    topics: tuple[str, ...] = ()
    curated_relations: tuple[str, ...] = ()
    evidence: tuple[Evidence, ...] = ()
    status: CandidateStatus = CandidateStatus.ACTIVE
    public: bool = True
    distance_meters: float | None = None

    def __post_init__(self) -> None:
        if self.distance_meters is not None and (
            not isfinite(self.distance_meters) or self.distance_meters < 0
        ):
            raise ValueError("distance_meters must be nonnegative")


@dataclass(frozen=True)
class BaselineRequest:
    dataset_revision: str
    region_code: str
    query_text: str
    query_tokens: tuple[str, ...]
    topics: tuple[str, ...]
    excluded_refs: frozenset[str] = field(default_factory=frozenset)
    pinned_refs: tuple[str, ...] = ()
    nearby_radius_meters: float | None = None
    ranking_version: str = "baseline-ranking-v1"
    dictionary_version: str = "journey-taxonomy-v1"

    def __post_init__(self) -> None:
        if not self.dataset_revision.strip():
            raise ValueError("dataset_revision must not be blank")
        if not self.region_code.strip():
            raise ValueError("region_code must not be blank")
        if self.nearby_radius_meters is not None and (
            not isfinite(self.nearby_radius_meters) or self.nearby_radius_meters <= 0
        ):
            raise ValueError("nearby_radius_meters must be positive")
        if len(self.pinned_refs) > 3 or len(set(self.pinned_refs)) != len(self.pinned_refs):
            raise ValueError("pinned_refs must contain at most three unique refs")


@dataclass(frozen=True)
class ScoredCandidate:
    candidate: Candidate
    exact_name_match: bool
    lexical: float
    topic: float
    evidence: float
    distance: float
    score: float
