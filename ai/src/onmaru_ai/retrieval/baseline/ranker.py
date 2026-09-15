from __future__ import annotations

from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from enum import StrEnum

from .models import BaselineRequest, Candidate, HardFilterReason, ScoredCandidate
from .scoring import filter_reason, score_candidate

RANKED_LIMIT = 30
PROPOSAL_LIMIT = 12
BOARD_LIMIT = 3
DIVERSITY_PENALTY = 0.15
CATEGORY_SOFT_CAP = 2


class BaselineConstraintCode(StrEnum):
    PIN_EXCLUDED = "PIN_EXCLUDED"
    PIN_UNAVAILABLE = "PIN_UNAVAILABLE"


class BaselineConstraintError(ValueError):
    def __init__(self, code: BaselineConstraintCode, refs: tuple[str, ...]) -> None:
        super().__init__(f"{code.value}: {', '.join(refs)}")
        self.code = code
        self.refs = refs


@dataclass(frozen=True)
class RankedCandidate:
    candidate: Candidate
    base_score: float
    diversity_score: float


@dataclass(frozen=True)
class BaselineResult:
    dataset_revision: str
    ranking_version: str
    dictionary_version: str
    ranked: tuple[RankedCandidate, ...]
    proposal: tuple[RankedCandidate, ...]
    board: tuple[RankedCandidate, ...]
    excluded_reasons: tuple[tuple[str, HardFilterReason], ...]

    @property
    def ranked_refs(self) -> tuple[str, ...]:
        return tuple(item.candidate.ref for item in self.ranked)

    @property
    def proposal_refs(self) -> tuple[str, ...]:
        return tuple(item.candidate.ref for item in self.proposal)

    @property
    def board_refs(self) -> tuple[str, ...]:
        return tuple(item.candidate.ref for item in self.board)


class BaselineRanker:
    def rank(
        self,
        request: BaselineRequest,
        candidates: Sequence[Candidate],
    ) -> BaselineResult:
        self._validate_pin_exclusion(request)
        eligible, excluded = self._partition(request, candidates)
        deduplicated = self._deduplicate(eligible)
        self._validate_pin_availability(request, deduplicated)

        ranked = tuple(self._as_ranked(item) for item in deduplicated[:RANKED_LIMIT])
        proposal = self._select_stage(
            request, deduplicated, PROPOSAL_LIMIT, apply_category_cap=True
        )
        board = self._select_stage(request, deduplicated, BOARD_LIMIT, apply_category_cap=True)

        return BaselineResult(
            dataset_revision=request.dataset_revision,
            ranking_version=request.ranking_version,
            dictionary_version=request.dictionary_version,
            ranked=ranked,
            proposal=proposal,
            board=board,
            excluded_reasons=tuple(sorted(excluded, key=lambda item: (item[0], item[1].value))),
        )

    def _partition(
        self,
        request: BaselineRequest,
        candidates: Sequence[Candidate],
    ) -> tuple[list[ScoredCandidate], list[tuple[str, HardFilterReason]]]:
        eligible: list[ScoredCandidate] = []
        excluded: list[tuple[str, HardFilterReason]] = []
        for candidate in candidates:
            reason = filter_reason(candidate, request)
            if reason is None:
                eligible.append(score_candidate(candidate, request))
            else:
                excluded.append((candidate.ref, reason))
        eligible.sort(key=self._base_sort_key)
        return eligible, excluded

    def _deduplicate(self, candidates: Sequence[ScoredCandidate]) -> list[ScoredCandidate]:
        selected: dict[str, ScoredCandidate] = {}
        for candidate in candidates:
            selected.setdefault(candidate.candidate.ref, candidate)
        return list(selected.values())

    def _select_stage(
        self,
        request: BaselineRequest,
        candidates: Sequence[ScoredCandidate],
        limit: int,
        *,
        apply_category_cap: bool,
    ) -> tuple[RankedCandidate, ...]:
        by_ref = {item.candidate.ref: item for item in candidates}
        selected = [by_ref[ref] for ref in request.pinned_refs]
        remaining = [item for item in candidates if item.candidate.ref not in request.pinned_refs]
        category_cap = apply_category_cap and len(request.topics) > 1

        self._fill(selected, remaining, limit, category_cap=category_cap)
        if len(selected) < min(limit, len(candidates)) and category_cap:
            selected_refs = {item.candidate.ref for item in selected}
            relaxed_remaining = [
                item for item in remaining if item.candidate.ref not in selected_refs
            ]
            self._fill(selected, relaxed_remaining, limit, category_cap=False)

        return tuple(
            RankedCandidate(
                candidate=item.candidate,
                base_score=item.score,
                diversity_score=self._diversity_score(item, selected[:index]),
            )
            for index, item in enumerate(selected[:limit])
        )

    def _fill(
        self,
        selected: list[ScoredCandidate],
        remaining: list[ScoredCandidate],
        limit: int,
        *,
        category_cap: bool,
    ) -> None:
        while remaining and len(selected) < limit:
            category_counts = Counter(item.candidate.category for item in selected)
            selectable = [
                item
                for item in remaining
                if not category_cap or category_counts[item.candidate.category] < CATEGORY_SOFT_CAP
            ]
            if not selectable:
                return
            chosen = min(
                selectable,
                key=lambda item: (
                    not item.exact_name_match,
                    -self._diversity_score(item, selected),
                    item.candidate.ref,
                ),
            )
            selected.append(chosen)
            remaining.remove(chosen)

    def _diversity_score(
        self,
        candidate: ScoredCandidate,
        selected: Sequence[ScoredCandidate],
    ) -> float:
        if candidate.exact_name_match or not selected:
            return candidate.score
        similarity = max((self._topic_jaccard(candidate, item) for item in selected), default=0.0)
        return candidate.score - DIVERSITY_PENALTY * similarity

    @staticmethod
    def _topic_jaccard(left: ScoredCandidate, right: ScoredCandidate) -> float:
        left_topics = frozenset(left.candidate.topics)
        right_topics = frozenset(right.candidate.topics)
        union = left_topics.union(right_topics)
        if not union:
            return 0.0
        return len(left_topics.intersection(right_topics)) / len(union)

    @staticmethod
    def _base_sort_key(item: ScoredCandidate) -> tuple[bool, float, str, str]:
        return (
            not item.exact_name_match,
            -item.score,
            item.candidate.ref,
            item.candidate.name,
        )

    @staticmethod
    def _as_ranked(item: ScoredCandidate) -> RankedCandidate:
        return RankedCandidate(
            candidate=item.candidate,
            base_score=item.score,
            diversity_score=item.score,
        )

    @staticmethod
    def _validate_pin_exclusion(request: BaselineRequest) -> None:
        conflicts = tuple(ref for ref in request.pinned_refs if ref in request.excluded_refs)
        if conflicts:
            raise BaselineConstraintError(BaselineConstraintCode.PIN_EXCLUDED, conflicts)

    @staticmethod
    def _validate_pin_availability(
        request: BaselineRequest,
        candidates: Sequence[ScoredCandidate],
    ) -> None:
        available = {item.candidate.ref for item in candidates}
        missing = tuple(ref for ref in request.pinned_refs if ref not in available)
        if missing:
            raise BaselineConstraintError(BaselineConstraintCode.PIN_UNAVAILABLE, missing)
