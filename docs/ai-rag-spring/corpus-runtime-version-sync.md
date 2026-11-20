# Corpus Runtime Version Sync & 1st-Launch Pre-Open Checklist

> 상태: 1차 오픈 운영 절차 합의안 (작성일: 2026-11-20)
> 대상: `OnMaru-backend` Spring API (`onmaru.ai.versions.*`) ↔ `onmaru-ai-engine` (`feature/local-e5-embeddings`)

이 문서는 OnMaru Plan/Execute 1차 오픈 시 AI Engine의 활성 corpus/런타임 버전 4개 변수를 Spring 설정과 안전하게 동기화하기 위한 출처, 사전 게이트, 업데이트 순서, 그리고 비정상/미준비 상태(`reviewed_facts = 0`, heartbeat stale)에서의 장애/fallback 계약을 정의한다.

---

## 1. 네 개 런타임 버전 변수의 출처와 매핑

| Spring 환경 변수 | Spring 설정 키 (`application.yaml`) | 계약 필드 (`AiJourneyContracts.Versions`) | 출처 (`onmaru-ai-engine`) | 설명 |
|---|---|---|---|---|
| `ONMARU_AI_CORPUS_REVISION` | `onmaru.ai.versions.corpus-revision` | `corpusRevision` | `runtime-version` 스크립트 출력 (`CORPUS_REVISION`, DB `ai.active_corpus.revision_id`) | 현재 활성화된 코퍼스 리비전 식별자 (예: `rev-20261115025249`) |
| `ONMARU_AI_CONFIG_HASH` | `onmaru.ai.versions.config-hash` | `configHash` | `runtime-version` 스크립트 출력 (`CONFIG_HASH`) | 임베딩 프로파일(`multilingual-E5-small ONNX INT8`), 검색/라우터 설정의 SHA-256 해시 |
| `ONMARU_AI_QUESTION_DICTIONARY_VERSION` | `onmaru.ai.versions.question-dictionary-version` | `questionDictionaryVersion` | `runtime-version` 스크립트 출력 (`QUESTION_DICTIONARY_VERSION`) | 질문 사전/분류 사전 버전 (SafeQuery & Plan 호환성 검증용) |
| `ONMARU_AI_SOURCE_REVOCATION_SEQUENCE` | `onmaru.ai.versions.source-revocation-sequence` | `sourceRevocationSequence` | `runtime-version` 스크립트 출력 (`SOURCE_REVOCATION_SEQUENCE`, DB `ai.active_corpus.revocation_sequence`) | 공개/철회 반영 시퀀스 번호 |

관련 구현 및 설정 위치:
- Spring 바인딩: [`AiPlanVersionsProperties`](../../apps/spring-api/src/main/java/com/yrootlab/onmaru/integration/ai/plan/AiPlanVersionsProperties.java), [`ConfigurableCurrentVersions`](../../apps/spring-api/src/main/java/com/yrootlab/onmaru/integration/ai/plan/ConfigurableCurrentVersions.java)
- Spring 기본값(placeholder): [`application.yaml`](../../apps/spring-api/src/main/resources/application.yaml) (`corpus-1`, `sha256:aaaa...`, `questions-1`, `7`)
- 인프라 주입: [`infra/lightsail/compose.yaml`](../../infra/lightsail/compose.yaml), [`infra/lightsail/staging/compose.yaml`](../../infra/lightsail/staging/compose.yaml)
- 계약 검증:
  - `/plan` 단계: [`DefaultPlanAuthorityGate`](../../modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/plan/DefaultPlanAuthorityGate.java)가 Spring `CurrentVersions`와 AI `/plan` 응답 `versions`의 완전 일치를 검증
  - `/execute` 단계: [`ValidatingExecutionResultMapper`](../../modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/plan/ValidatingExecutionResultMapper.java)가 승인된 plan과 `/execute` 결과(`result`, `proposal`, `evidence`)의 `versions` 완전 일치를 재검증

---

## 2. 동기화 및 플래그 활성화 사전 게이트 (Pre-Sync & Pre-Open Gates)

아래 5개 조건을 **모두** 만족하기 전에는 `ONMARU_AI_CORPUS_REVISION` 등 4개 버전 변수를 운영에 동기화하지 않으며, **`ONMARU_AI_PLAN_EXECUTE_ENABLED`는 반드시 `false`를 유지**한다.

1. **DB `pg_search 0.27.0` + `pdb.ngram(2,3)` 검증 완료**
   - 운영/staging PostgreSQL 17 인스턴스에 `pg_search 0.27.0` 확장이 설치되고, `ai.chunks.search_text` 대상 `pdb.ngram(2,3)` BM25 인덱스·한글 검색·필터·top-K가 정상 동작해야 한다.
2. **임베딩 프로파일 일치**
   - 코퍼스와 질의 임베딩 모두 AI Engine `feature/local-e5-embeddings`의 동일한 `multilingual-E5-small ONNX INT8` 프로파일을 사용해야 한다. (1차 오픈은 Hybrid+RRF 기준이며 Cross-Encoder reranker는 보류)
3. **승인된 `ai.reviewed_facts >= 1` 적재 완료**
   - 사실 검수와 적재는 **반드시 사람 승인 뒤에만** 진행한다.
   - `SELECT COUNT(*) FROM ai.reviewed_facts;` (승인된 사실) >= 1 이어야 한다. `0`건인 상태에서는 동기화 및 1차 오픈을 금지한다.
4. **AI Engine Heartbeat 정상 (Not Stale)**
   - AI Engine의 index/runtime heartbeat가 최신 상태로 건강해야 하며, stale 상태에서는 버전 동기화를 진행하지 않는다.
5. **Spring 타임아웃/예산 정합성**
   - `ONMARU_AI_TIMEOUT` (`15s` compose / `12s` default) < `ExplorationService.RUN_BUDGET` / `RUN_LEASE` (`20s`).
   - 429 (`AI_QUOTA_EXCEEDED` + `Retry-After`) 발생 시 Spring 클라이언트는 내부 재시도 sleep으로 run 예산을 소진하지 않고 즉시 `AiProposalException(AI_QUOTA_EXCEEDED, retryAfterMs)`를 전파한다.

---

## 3. 버전 동기화 및 오픈 업데이트 순서

동기화는 반드시 **AI Engine / DB 준비 → 버전 추출 → Spring 설정 반영 → Health/Contract 확인 → 승인 후 플래그 활성화** 순서를 따른다.

```text
1. [Human Gate] 사실 검수 및 승인 완료 → ai.reviewed_facts 적재 (>= 1)
2. [DB & AI Engine] feature/local-e5-embeddings 기준 corpus 활성화 + pg_search BM25 인덱스 구축/갱신 + heartbeat 정상 확인
3. [AI Engine] runtime-version 스크립트 실행 → 4개 값 확인
   - CORPUS_REVISION
   - CONFIG_HASH
   - QUESTION_DICTIONARY_VERSION
   - SOURCE_REVOCATION_SEQUENCE
4. [Gate Check] heartbeat = healthy AND approved reviewed_facts >= 1 확인 (하나라도 미충족 시 중단)
5. [Spring .env] ONMARU_AI_PLAN_EXECUTE_ENABLED=false 유지 상태에서 4개 환경 변수 업데이트
   - ONMARU_AI_CORPUS_REVISION=<CORPUS_REVISION>
   - ONMARU_AI_CONFIG_HASH=<CONFIG_HASH>
   - ONMARU_AI_QUESTION_DICTIONARY_VERSION=<QUESTION_DICTIONARY_VERSION>
   - ONMARU_AI_SOURCE_REVOCATION_SEQUENCE=<SOURCE_REVOCATION_SEQUENCE>
6. [Spring Rollout] Spring 컨테이너 재기동 (예: spring-green / spring-blue) 및 /actuator/health 확인
7. [Approval Gate] staging 또는 검증 완료 + 별도 배포 승인 획득 후 ONMARU_AI_PLAN_EXECUTE_ENABLED=true 전환
```

주의 사항:
- Spring 4개 환경 변수만 먼저 바꾸고 AI Engine/DB의 활성 corpus가 아직 이전 상태라면, `DefaultPlanAuthorityGate`가 `AI_INVALID_RESPONSE`로 차단하여 baseline fallback으로 강등된다.
- 반대로 AI Engine만 새 corpus를 활성화하고 Spring 환경 변수를 placeholder(`corpus-1`, `sha256:aaaa...`, `7`)로 두면 동일하게 버전 불일치로 실패한다. 따라서 **AI Engine corpus 활성화 → Spring .env 4개 값 동기화 & 재기동** 순서를 반드시 지킨다.

---

## 4. `reviewed_facts == 0` 또는 Heartbeat Stale 시 동작 계약

운영 중 또는 사전 점검에서 `ai.reviewed_facts`가 `0`건이거나 heartbeat가 stale한 경우에도 **절대 가짜 LLM 추천 성공(`COMPLETED_LLM` / 빈 근거 추천)으로 처리하지 않는다.**

| 장애/미준비 조건 | AI Engine → Spring 전달 형태 | Spring 처리 결과 |
|---|---|---|
| AI Engine HTTP `503` / `422` (`REVIEWED_FACTS_EMPTY`, `HEARTBEAT_STALE` 등) | HTTP 422 → `AI_INVALID_RESPONSE`, HTTP 503 → `AI_SERVICE_UNAVAILABLE` | `AiProposalException` 발생 → 안전한 baseline 후보가 있으면 `engine=BASELINE`, `degradedReason` 설정 후 완료 / 안전한 baseline이 없으면 `status=FAILED`, `error.code=<degradedReason>` 명시적 실패 및 빈 보드 저장 금지 |
| `proposal.failureStage != null` 또는 `retrievalStatus == "FAILED"` | `/execute` 결과 검증 | [`ValidatingExecutionResultMapper`](../../modules/journey/src/main/java/com/yrootlab/onmaru/journey/worker/plan/ValidatingExecutionResultMapper.java)가 `AI_INVALID_RESPONSE`로 거부 |
| 근거(`evidence.allowedPlaceIds`)는 있으나 `places`가 비어 있음 | `/execute` 결과 검증 | `ValidatingExecutionResultMapper`가 가짜 `NO_RESULTS` 성공을 차단(`AI_INVALID_RESPONSE`) |
| `versions` 불일치 (`corpusRevision`, `configHash`, `questionDictionaryVersion`, `sourceRevocationSequence`) | `/plan` 또는 `/execute` 응답 | `DefaultPlanAuthorityGate` / `ValidatingExecutionResultMapper`가 `AI_INVALID_RESPONSE`로 거부 |
| Gemini quota 초과 (`HTTP 429`, `{"code":"AI_QUOTA_EXCEEDED"}`, `Retry-After`) | `/plan` 또는 `/execute` 응답 | `retryAfterMs` 보존 + 안전 baseline 있으면 `COMPLETED` (`engine=BASELINE`, `degradedReason=AI_QUOTA_EXCEEDED`, `retryAfterMs`), 없으면 `FAILED` (`error.code=AI_QUOTA_EXCEEDED`, `retryAfterMs`, SSE `run.terminal` `status=FAILED`) |
