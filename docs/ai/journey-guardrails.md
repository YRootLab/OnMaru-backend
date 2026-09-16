# 여정 AI의 안전한 입력·제안 harness

2026-09-12. 상태: 구현 전 설계 계약. 이 문서는 한옥·전통시장·Odii 기반 **여정 탐색**만 다룬다. 문화 일반 Q&A와 문화 콘텐츠 페이지는 [project roadmap](../../project-roadmap.md)의 장기 옵션이며 이 pipeline의 허용 기능이 아니다.

오픈소스 패턴을 반영한 state/adapter/prompt/eval/tool 설계와 도입 순서는 [AI application architecture](application-architecture.md)를 따른다. 이 문서는 그 설계 중 public intake와 provider proposal contract를 구체화한다.

## 목표와 비목표

목표는 자유 입력을 받되 AI가 서비스 범위, 관광 사실, 후보 집합, 사용자 소유권을 우회하지 못하게 하는 것이다. Gemini는 여행 여정을 표현하는 제한된 제안자이고, Spring은 정책 판정·지역 확인·후보 검색·결과 확정의 정답이다.

- 허용: 전국의 한옥, 한옥 숙박, 한옥 카페, 한옥 체험, 전통시장, 그리고 이 주제와 연결되는 Odii 이야기를 조합한 국내 여행 여정 탐색.
- 제외: 문화 일반 Q&A, 건강/법률/금융 조언, 코딩, 숙제, 일반 잡담, 웹 검색, 실시간 날씨·교통·혼잡 보장.
- 제외: 모델의 외부 URL/Google Search/Maps/코드 실행/임의 SQL 호출, 쓰기 tool, 다중 agent, LangChain/LangGraph 기본 도입.

LangGraph는 장기 실행·여러 모델 주도 tool·human-in-the-loop이 실제 요구되는 때만 별도 ADR로 검토한다. 현재 durable run은 Spring DB lifecycle이 소유하므로, 모델 orchestration framework가 이를 대체하지 않는다.

## 호출 경계와 adapter

```mermaid
flowchart LR
  FE[Browser] --> S[Spring exploration API]
  S --> G[Input policy and intake]
  G --> R[Canonical region and candidate search]
  R --> P[ProposalPort]
  P --> H[FastAPI journey proposal API]
  H --> A[LLMProviderAdapter]
  A --> M[GeminiAdapter or test fake]
  M --> A
  A --> H
  H --> P
  P --> V[Spring proposal validator]
  V --> FE
```

Spring core depends only on its consumer-owned `ProposalPort`; it does not import a Gemini SDK, LangChain, FastAPI DTO, or provider model name. The FastAPI service exposes one internal versioned contract and owns `LLMProviderAdapter`. `GeminiAdapter`, a deterministic fixture fake, and a future provider adapter implement the same interface. Gemini API migrations, model retirement, JSON mode changes, tokenizer changes, and SDK replacement stay inside the adapter.

`POST /internal/v1/journey/proposals` is service-to-service only. It is not a browser endpoint and accepts only a short-lived Spring service token whose audience is FastAPI, expiry is within the run deadline, and `jti` has not been replayed. It receives a deadline and trace context, never a browser credential. FastAPI has no Spring business-schema credential and no authority to read a wider catalog, change an exploration, consume a quota, or persist a user-visible board. Its separate `ai` schema credential, when RAG is enabled, is limited to FastAPI corpus/index tables.

The adapter uses a provider's structured JSON output capability, not model-selected function calls. A function call would require the application to execute model-selected work; that is unnecessary and expands the attack surface here. When RAG is approved, FastAPI alone ingests Spring's revision-pinned corpus export and its read-only `EvidenceRetriever` enforces the request allowlist; it cannot fetch URLs or call provider tools.

## Intake policy before any model call

Spring handles the following decision tree synchronously before creating an AI run. Inputs rejected in the first three branches are neither sent to FastAPI nor retained as an exploration turn.

```text
normalize and size/rate check
  -> privacy or credential pattern: PRIVACY_REDACT_REQUIRED
  -> high-confidence safety policy match: SAFETY_BLOCKED
  -> outside journey taxonomy: JOURNEY_SCOPE_UNSUPPORTED
  -> region missing or ambiguous: create clarification run
  -> recognized journey intent: candidate retrieval and optional AI admission
```

Normalization is NFC, trimmed UTF-8 and bounded to the public request size. The policy engine rejects control-character abuse, duplicate/replayed commands, known credential shapes, and high-confidence personal identifiers before logging or persistence. It is not a claim that a finite forbidden-word list detects all harmful speech. Safety rules are versioned policy data plus targeted fixtures; Korean place names, cultural terms, and normal user sentiment must not be blocked merely because a broad keyword happened to match.

`JOURNEY_SCOPE_UNSUPPORTED` is returned for requests that cannot be converted to the taxonomy below. It returns a stable product message selected by FE, not an LLM answer: OnMaru helps build journeys around hanok, traditional markets, and Odii stories. `SAFETY_BLOCKED` and `PRIVACY_REDACT_REQUIRED` similarly return only code and retry guidance; no detailed moderation rationale is exposed.

```ts
type IntakeDecision =
  | { type: 'SAFETY_BLOCKED'; policyVersion: string }
  | { type: 'PRIVACY_REDACT_REQUIRED'; policyVersion: string }
  | { type: 'JOURNEY_SCOPE_UNSUPPORTED'; policyVersion: string }
  | { type: 'ASK_REGION'; choices: RegionChoice[] }
  | { type: 'ASK_CLARIFICATION'; clarification: Clarification }
  | { type: 'SEARCH_CANDIDATES'; intent: JourneyIntent };

type JourneyIntent = {
  regionCode: string;
  themes: ('HANOK' | 'HANOK_STAY' | 'HANOK_CAFE' | 'HANOK_EXPERIENCE' | 'MARKET' | 'ODII' | 'WALK' | 'REST')[];
  moods: ('QUIET' | 'LIVELY' | 'RAINY_DAY' | 'HEALING' | 'NONE')[];
  explicitConstraints: string[];
  excludedRefs: PlaceRef[];
};
```

Region is mandatory for the first board. A recognized region in the user sentence resolves through the canonical region dictionary. Missing, conflicting, or ambiguous regions produce one typed clarification at a time; the server does not infer a region from IP, GPS, popularity, or a nationwide top list. The first UI asks where the user wants to explore, but a sentence that already specifies a single canonical region skips that question.

The user must opt in before AI admission. The established daily AI cap is guest 2 and member 5 by KST date, plus global/provider concurrency and budget caps. Admission refusal consumes no quota. A provider-dispatched request consumes one quota unit even if the provider then fails. Public catalog browsing and the deterministic BASELINE engine remain available after an AI quota or provider failure.

## Deterministic search before generation

Spring converts only an eligible `JourneyIntent` into filters: published revision, canonical region, public eligibility, taxonomy category, exclusions, and explicit constraints. It applies those filters before ranking and resolves duplicate source records to canonical place IDs. It returns at most 12 candidate packages to FastAPI; each package contains an opaque place reference, title, category, and short revision-pinned evidence snippets. No raw browser query, member ID, guest token, exact location, saved list, OAuth/session material, or visit-review text is included.

The existing baseline algorithm remains the source of candidates and the non-AI fallback. An LLM cannot loosen a hard filter, add a place, resurrect a stale revision, remove a pin, or calculate travel time. Candidate generation failure returns the normal typed clarification or no-results response without an LLM call.

## Provider-neutral proposal contract

The internal request and response are JSON-schema versioned independently from provider SDK models. FastAPI validates the request with Pydantic after service-token authentication; Spring validates the response again before applying it.

```ts
type JourneyProposalRequest = {
  schemaVersion: 'internal.ai.v1';
  requestId: string;
  runId: string;
  candidateCount: number; // 0..12
};

type JourneyProposalResponse = {
  schemaVersion: 'internal.ai.v1';
  runId: string;
  outcome: 'PROPOSAL' | 'INITIAL_BOARD' | 'NO_RESULTS';
  orderedRefs: PlaceRef[];
};
```

The J03 baseline contract currently sends only bounded correlation and candidate cardinality. It deliberately does not send raw browser query, member ID, guest token, session material, saved-list data, or exact private location to FastAPI. The response has at most three `orderedRefs`, and Spring still owns final validation, fallback, and persistence.

The richer provider proposal contract with evidence reasons, pins/exclusions, proposal action, and state-version handling is reserved for the later proposal workflow and must not be mixed into this worker baseline. The model cannot return free-form markdown, executable markup, tool calls, URLs, new category values, or a user-facing error message.

FastAPI rejects malformed or schema-invalid provider output as `AI_INVALID_RESPONSE`. Spring then rechecks cardinality, IDs, evidence ownership and revision, current publication eligibility, pin/exclude state, exploration version, actor validity, and deadline before atomically saving a proposal. Any mismatch fails the run; it is never repaired by a hidden second prompt.

## Prompt engineering contract

The provider adapter builds a fixed prompt from three separated blocks:

1. **Policy block:** the model is a Korean journey proposal formatter; it may only use supplied candidates/evidence, may not answer general cultural Q&A, and must return the declared JSON schema.
2. **Few-shot block:** versioned, non-user examples for a quiet hanok walk, market plus Odii, candidate shortage, and a request requiring a constraint clarification. Examples contain synthetic IDs and never teach factual claims.
3. **Data block:** serialized `JourneyIntent`, pins, candidates, and evidence. All source text is labeled untrusted data; any instruction inside it must be ignored.

The provider receives a single request, temperature zero where supported, bounded input/output tokens, and the remaining run deadline. It has no conversation storage authority. Spring owns the exploration history and may pass only normalized, bounded structured state. Prompt, model alias, adapter version, candidate revision, taxonomy dictionary version, and safety policy version are recorded as non-sensitive telemetry for reproducibility.

## User-visible outcomes and API errors

Valid but incomplete exploration input receives the usual `202 RunAccepted`, followed by `COMPLETED + CLARIFICATION_REQUIRED` or `NO_RESULTS`. Requests blocked before an exploration is created return these public errors:

| HTTP | Code | FE action |
|---:|---|---|
| 422 | `JOURNEY_SCOPE_UNSUPPORTED` | Show the journey scope and example prompts. |
| 422 | `PRIVACY_REDACT_REQUIRED` | Ask the user to remove personal or credential information. |
| 422 | `SAFETY_BLOCKED` | Show a brief safety response; do not retry automatically. |
| 202 then COMPLETED | `AI_QUOTA_EXCEEDED` | Complete the same run with the deterministic candidate board and `engine=BASELINE`; no provider call occurs. |
| 202 then COMPLETED | `AI_INVALID_RESPONSE`, `AI_TIMEOUT`, provider/internal service failure | Complete the same run with the deterministic candidate board and `engine=BASELINE`; no second model call occurs. |

The FE never treats browser disconnect as cancellation or provider failure as a reason to silently rerun the model. It maps codes to reviewed Korean strings, renders the completed baseline board when available, and may label it as a basic exploration result without exposing raw provider-health details.

## Harness and verification

The adapter harness has three replaceable implementations: `FakeProposalAdapter` for deterministic tests, `GeminiAdapter` for opt-in environments, and a future provider adapter. Every adapter consumes and produces the same internal contract. Contract fixtures are sanitized and contain no production key, member identifier, raw query, or private location.

Required fixture classes are normal journey requests; missing/conflicting region; category and exclusion conflicts; unsupported culture Q&A and general chat; injection attempts embedded in user input and source text; high-confidence safety/privacy inputs; candidate shortage; stale/deleted evidence; quota/admission rejection; timeout; malformed JSON; candidate ID injection; invalid evidence mapping; cancellation and deadline races.

Release gates require zero violations of allowed ID, pin, exclusion, and published-revision invariants. They also require fixed safety/scope fixture recall, no raw user input in adapter logs, structured-output parse success, bounded token/call counts, and comparison against the deterministic baseline on the frozen held-out journey set. The model is not enabled merely because the adapter can call Gemini.
