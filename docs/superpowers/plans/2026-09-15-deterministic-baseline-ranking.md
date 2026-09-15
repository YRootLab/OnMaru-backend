# Deterministic Baseline Ranking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Issue #97의 revision-pinned 후보를 결정적으로 필터링하고 `rank30 -> proposal12 -> board3` 순서로 반환하는 FastAPI baseline ranking 모듈을 구현한다.

**Architecture:** `onmaru_ai.retrieval.baseline`은 HTTP나 DB에 의존하지 않는 순수 domain 모듈이다. Spring payload 또는 revision export에서 이미 정규화된 후보를 immutable dataclass로 받고, hard filter를 점수 계산보다 먼저 적용한 뒤 ADR-0007의 v1 점수·canonical dedup·pin 보존·diversity 규칙을 적용한다. 결과에는 dataset/ranking/dictionary version과 제외 사유를 포함해 같은 입력의 재현성을 검증한다.

**Tech Stack:** Python 3.12, standard-library dataclasses/enum/unicodedata, pytest, Ruff, mypy strict

## Global Constraints

- Source of truth는 GitHub Issue #97, `docs/decisions/0007-baseline-optional-rag.md`, `docs/ai/retrieval-and-rag.md`다.
- active dataset revision, region, public eligibility, tombstone, exclusions, evidence provenance는 ranking 전에 hard filter한다.
- 점수는 `0.45L + 0.25T + 0.20E + 0.10D`이고 feature가 없을 때 재가중하지 않는다.
- 정렬 tie-breaker는 canonical ref 오름차순이며 입력 순서에 의존하지 않는다.
- canonical 후보는 top 30, proposal 후보는 pin을 포함해 최대 12, baseline board는 최대 3이다.
- pin은 hard filter를 우회하지 않으며 pin과 exclude 충돌 또는 pin 부재를 조용히 무시하지 않는다.
- 외부 LLM, vector DB, embedding, HTTP endpoint, Spring business DB 접근은 범위 밖이다.

---

### Task 1: Immutable candidate model, hard filters, and v1 scoring

**Files:**
- Create: `ai/src/onmaru_ai/retrieval/__init__.py`
- Create: `ai/src/onmaru_ai/retrieval/baseline/__init__.py`
- Create: `ai/src/onmaru_ai/retrieval/baseline/models.py`
- Create: `ai/src/onmaru_ai/retrieval/baseline/scoring.py`
- Create: `ai/tests/retrieval/baseline/test_scoring.py`

**Interfaces:**
- Consumes: revision-pinned canonical candidates supplied by Spring payload or a verified export.
- Produces: `BaselineRequest`, `Candidate`, `Evidence`, `CandidateStatus`, `HardFilterReason`, `ScoredCandidate`, `filter_reason(candidate, request)`, and `score_candidate(candidate, request)`.

- [x] **Step 1: Write failing scoring and filtering tests**

Test that a public candidate from the requested revision/region with provenance is accepted, and parameterize rejection for revision mismatch, region mismatch, excluded ref, non-public status, tombstone, and missing provenance. Assert lexical/topic/evidence/distance feature values and total score without feature reweighting.

```python
def test_scores_with_documented_v1_weights() -> None:
    request = request_for(query_tokens=("한옥", "산책"), topics=("HANOK", "WALK"))
    candidate = candidate_for(
        name="고요한 한옥",
        summary="한옥 마당 산책",
        topics=("HANOK",),
        distance_meters=500,
    )

    scored = score_candidate(candidate, request)

    assert scored.lexical == pytest.approx(1.0)
    assert scored.topic == pytest.approx(0.5)
    assert scored.evidence == pytest.approx(1.0)
    assert scored.distance == pytest.approx(0.5)
    assert scored.score == pytest.approx(0.825)
```

- [x] **Step 2: Run tests and verify RED**

Run: `cd ai && uv run pytest tests/retrieval/baseline/test_scoring.py -q`

Expected: collection fails because `onmaru_ai.retrieval.baseline` does not exist.

- [x] **Step 3: Implement immutable models and pure scoring functions**

Use frozen dataclasses. Normalize matching text with NFC + `casefold()`. Evidence qualifies only when both `source_id` and nonblank `text` exist. `filter_reason` returns one stable enum reason or `None`; `score_candidate` rejects a hard-filtered candidate and returns the five score fields for an eligible candidate.

```python
@dataclass(frozen=True)
class ScoredCandidate:
    candidate: Candidate
    exact_name_match: bool
    lexical: float
    topic: float
    evidence: float
    distance: float
    score: float
```

- [x] **Step 4: Run Task 1 tests and quality checks**

Run: `cd ai && uv run pytest tests/retrieval/baseline/test_scoring.py -q && uv run ruff check src/onmaru_ai/retrieval tests/retrieval && uv run mypy src/onmaru_ai/retrieval tests/retrieval`

Expected: all commands exit 0.

### Task 2: Deterministic rank30, dedup, pins, diversity, and board selection

**Files:**
- Create: `ai/src/onmaru_ai/retrieval/baseline/ranker.py`
- Create: `ai/tests/retrieval/baseline/test_ranker.py`
- Modify: `ai/src/onmaru_ai/retrieval/baseline/__init__.py`

**Interfaces:**
- Consumes: Task 1 `BaselineRequest`, `Candidate`, `ScoredCandidate`, and scoring/filter functions.
- Produces: `BaselineRanker.rank(request, candidates) -> BaselineResult`, `BaselineConstraintError`, `RankedCandidate`, and stable exclusion audit entries.

- [x] **Step 1: Write failing pipeline tests**

Cover all of these behaviors with real candidates and no mocks:

```python
def test_same_revision_and_input_are_independent_of_candidate_order() -> None:
    first = BaselineRanker().rank(request, candidates)
    second = BaselineRanker().rank(request, tuple(reversed(candidates)))
    assert first == second

def test_pin_outside_top_thirty_is_preserved_in_proposal_and_board() -> None:
    result = BaselineRanker().rank(request_with_pin("place-40"), forty_candidates())
    assert "place-40" in result.proposal_refs
    assert "place-40" in result.board_refs
```

Also assert canonical duplicate selection is deterministic, exact-name priority, same-category soft cap, top 30/12/3 limits, hard-filter audit reasons, and explicit errors for missing/ineligible/conflicting pins.

- [x] **Step 2: Run tests and verify RED**

Run: `cd ai && uv run pytest tests/retrieval/baseline/test_ranker.py -q`

Expected: collection fails because `BaselineRanker` does not exist.

- [x] **Step 3: Implement deterministic ranking pipeline**

Sort initial scored rows by exact-name match descending, score descending, ref ascending. Deduplicate by ref using that order and retain 30. Validate pins against the complete eligible deduplicated set, prepend pins in request order, then greedily select non-pins using `score - 0.15 * max(topic_jaccard)` with ref as tie-breaker. Apply category maximum 2 only when multiple query topics are requested; relax only that soft cap when it prevents filling the requested stage. Return proposal maximum 12 and board maximum 3.

```python
class BaselineRanker:
    def rank(
        self,
        request: BaselineRequest,
        candidates: Sequence[Candidate],
    ) -> BaselineResult:
        raise NotImplementedError
```

- [x] **Step 4: Run Task 1 and Task 2 tests and quality checks**

Run: `cd ai && uv run pytest tests/retrieval/baseline -q && uv run ruff check src/onmaru_ai/retrieval tests/retrieval && uv run mypy src/onmaru_ai/retrieval tests/retrieval`

Expected: all commands exit 0.

### Task 3: Held-out fixtures, regression harness, and project records

**Files:**
- Create: `ai/tests/retrieval/baseline/fixtures/determinism.json`
- Create: `ai/tests/retrieval/baseline/fixtures/hard-filters.json`
- Create: `ai/tests/retrieval/baseline/fixtures/pin-diversity.json`
- Create: `ai/tests/retrieval/baseline/test_fixtures.py`
- Modify: `CHANGELOG.md`
- Modify: `handoff.md`
- Create: `troubleshooting-worklog/26.09.15 deterministic-baseline-ranking.md`

**Interfaces:**
- Consumes: public `BaselineRanker` API and sanitized JSON fixture documents.
- Produces: reproducible held-out regression evidence tied to Issue #97 and session restart context.

- [x] **Step 1: Write fixture loader test before creating fixtures**

The test loads every JSON file, constructs typed requests/candidates, runs the ranker for both declared input orders, and compares `rankedRefs`, `proposalRefs`, `boardRefs`, and `excludedReasons` to expected values.

- [x] **Step 2: Run fixture test and verify RED**

Run: `cd ai && uv run pytest tests/retrieval/baseline/test_fixtures.py -q`

Expected: test fails because the declared fixture files do not exist.

- [x] **Step 3: Add sanitized fixtures and project records**

Fixtures use synthetic refs and evidence only. The worklog records Issue/dependency triage, documented score contract, every RED failure, edge cases, review findings, and exact verification commands. The handoff top section records branch, Issue #97, touched paths, verification, PR state, and remaining review/merge action.

- [x] **Step 4: Run complete repository verification**

Run: `cd ai && uv run ruff check . && uv run mypy && uv run pytest`

Run: `./gradlew test --no-daemon`

Run: `node --test scripts/test/*.test.mjs && node scripts/verify-planning-inputs.mjs && node scripts/validate-odii-fixtures.mjs && bash scripts/verify-contracts`

Run: `node scripts/print-branch-issue.mjs && git diff --check`

Expected: every command exits 0 and the branch parser prints `97`.

- [ ] **Step 5: Review and prepare PR**

Review Issue #97 acceptance criteria, `git diff`, input-order determinism, allowlist/filter invariants, and test gaps. Commit using Conventional Commits, push `feature/97-deterministic-baseline-ranking`, and create a Korean PR into `develop` with `Closes #97` and verification evidence. Do not close #97 before merge.
