from __future__ import annotations

import unicodedata

from .models import (
    BaselineRequest,
    Candidate,
    CandidateStatus,
    HardFilterReason,
    ScoredCandidate,
)


def filter_reason(candidate: Candidate, request: BaselineRequest) -> HardFilterReason | None:
    if candidate.revision_id != request.dataset_revision:
        return HardFilterReason.REVISION_MISMATCH
    if candidate.region_code != request.region_code:
        return HardFilterReason.REGION_MISMATCH
    if candidate.ref in request.excluded_refs:
        return HardFilterReason.EXCLUDED
    if not candidate.public:
        return HardFilterReason.NOT_PUBLIC
    if candidate.status is CandidateStatus.TOMBSTONE:
        return HardFilterReason.TOMBSTONE
    if not any(item.has_provenance for item in candidate.evidence):
        return HardFilterReason.MISSING_EVIDENCE
    return None


def score_candidate(candidate: Candidate, request: BaselineRequest) -> ScoredCandidate:
    reason = filter_reason(candidate, request)
    if reason is not None:
        raise ValueError(f"candidate failed hard filter: {reason.value}")

    normalized_query = _normalize(request.query_text)
    names = tuple(_normalize(value) for value in (candidate.name, *candidate.aliases) if value)
    exact_name_match = any(name and name in normalized_query for name in names)

    searchable = _normalize(" ".join((candidate.name, *candidate.aliases, candidate.summary or "")))
    tokens = tuple(_normalize(token) for token in request.query_tokens if token.strip())
    lexical = sum(token in searchable for token in tokens) / len(tokens) if tokens else 0.0

    requested_topics = frozenset(request.topics)
    topic = (
        len(requested_topics.intersection(candidate.topics)) / len(requested_topics)
        if requested_topics
        else 0.0
    )
    evidence = (0.5 if candidate.summary and candidate.summary.strip() else 0.0) + (
        0.5 if candidate.topics or candidate.curated_relations else 0.0
    )
    distance = _distance_feature(candidate, request)
    score = 0.45 * lexical + 0.25 * topic + 0.20 * evidence + 0.10 * distance

    return ScoredCandidate(
        candidate=candidate,
        exact_name_match=exact_name_match,
        lexical=lexical,
        topic=topic,
        evidence=evidence,
        distance=distance,
        score=score,
    )


def _distance_feature(candidate: Candidate, request: BaselineRequest) -> float:
    radius = request.nearby_radius_meters
    distance = candidate.distance_meters
    if radius is None or distance is None:
        return 0.0
    return max(0.0, 1.0 - distance / radius)


def _normalize(value: str) -> str:
    return unicodedata.normalize("NFC", value).casefold().strip()
