# OnMaru 문서 안내와 설계 기준

> **현재 단계: 구현 전 설계.** 이 저장소의 문서는 구현 방향과 검증 기준을 정하지만, 코드·실행 migration·배포된 API·운영 환경의 존재를 뜻하지 않는다. 구현 증거가 생길 때까지 문서의 상태를 과장하지 않는다.

## 이 문서가 답하는 질문

OnMaru 문서는 한 폴더에 모두 넣지 않는다. 문서마다 서로 다른 질문과 효력이 있기 때문이다.

```text
Planning ──무엇을/왜/언제──> Decision ──무엇을 선택했나──> Design contract ──어떻게 만들까──> Evidence ──실제로 동작하는가
```

| 구분 | 답하는 질문 | 구현에 대한 효력 |
|---|---|---|
| 기획 | 무엇을, 누구를 위해, 어느 순서로 만들까? | 범위·우선순위·착수 게이트를 제시한다. |
| ADR | 장기 구조 선택과 이유는 무엇인가? | `accepted` ADR만 장기 구조의 구속력 있는 기준이다. |
| 책임별 설계·계약 | 각 서비스·DB·API·운영은 어떻게 동작해야 하나? | 구현과 테스트가 따라야 할 현재 목표 설계다. |
| 외부 입력·조사 | 공급자와 FE는 무엇을 제공하는가? | 구현 전에 검증해야 할 입력 근거다. |
| 검토 이력 | 어떤 위험과 선택지를 검토했는가? | 판단 근거이며 current contract를 대체하지 않는다. |
| 실행 증거 | migration, test, CI, 배포·운영이 무엇을 입증하는가? | 구현됐다고 말할 수 있는 유일한 근거다. |

## 현재 문서 지도

| 경로 | 역할 | 현재 효력 / 읽을 때 주의 |
|---|---|---|
| [`planning/`](planning/README.md) | PRD, 출시 범위, readiness, Issue 후보, 과거 기획 검토 | 제품 범위와 착수 순서의 기준. 구조가 충돌하면 ADR와 책임별 설계가 우선한다. |
| [`decisions/`](decisions/README.md) | Architecture Decision Records | `accepted`만 확정 결정이다. `proposed`는 검토·승인 전이며, `drafts/`는 ADR 입력과 근거로 정식 ADR이 아니다. |
| [`architecture/`](architecture/README.md) | 전체 시스템 경계, Spring 모듈 DAG, 의존 방향 | 여러 runtime·domain에 걸친 구조 제약의 기준. |
| [`contracts/`](contracts/README.md) | 공개 REST, FE handoff, OpenAPI, JSON Schema, fixtures | FE/BE 및 Spring/FastAPI가 함께 지켜야 할 계약. 현재는 일부 영역만 기계 계약이 있다. |
| [`database/`](database/README.md) | DBML ERD, 도메인별 데이터 모델, migration/concurrency 설계 | DBML은 논리 설계 원본이다. 실행 SQL은 향후 `db/migration/`의 Flyway migration이 기준이 된다. |
| [`spring/`](spring/README.md) | Spring의 catalog, identity, journey 등 책임별 설계 | Spring source가 아니라 구현 전 설계 문서다. 향후 source root `spring/`과 혼동하지 않는다. |
| [`ai/`](ai/README.md) | FastAPI, provider boundary, prompt/eval, RAG·corpus sync | AI source가 아니라 구현 전 설계 문서다. 향후 source root `ai/`와 혼동하지 않는다. |
| [`operations/`](operations/README.md) | ingestion, LKG, run 복구, backup, observability, moderation | 운영 환경이 이미 구성됐다는 뜻이 아니라 공개 전 검증해야 할 절차다. |
| [`api/`](api/) | 관광·행정 등 외부 API 조사와 원천 분석 | public API 계약이 아니다. 실제 provider response·quota·licence는 adapter 구현 중 재검증한다. |
| [`specs/`](specs/) | FE 요구·data contract·traceability 입력 | 현재는 외부 저장소를 가리키는 symlink다. CI 재현용 snapshot/manifest 전환 전에는 로컬 입력 의존성이 남아 있다. |
| [`toFE/`](toFE/README.md) | FE 전환 영향과 체크리스트 | field-level current contract는 `contracts/`가 우선한다. |
| [`report/`](report/) | 날짜별 아키텍처·PRD 감사 보고서 | 검토 시점의 판단 기록이다. 최신 contract나 ADR을 대체하지 않는다. |

`planning/journey-exploration/`의 문서는 초기 탐색·7일 MVP 가설을 보존하는 **archive**다. 현재 여정 transport와 DTO는 [Public REST API](contracts/rest-api.md), [Journey OpenAPI](contracts/openapi/journey.openapi.yaml), [SSE schema](contracts/schemas/journey-sse-event.schema.json)를 따른다.

## 개발 시작 시 읽는 순서

### R1/R2 Spring API를 구현할 때

1. [구현 준비도](planning/implementation-readiness.md)에서 해당 레이어의 열린 gate를 확인한다.
2. 관련 `accepted` ADR와 `proposed` ADR를 읽는다. `proposed`는 구현 중 변경 가능하므로 승인 여부를 Issue에 기록한다.
3. [Spring 설계](spring/README.md), [모듈 경계](architecture/module-boundaries.md), [DB 설계](database/README.md)를 읽는다.
4. [REST 계약](contracts/rest-api.md)과 해당 OpenAPI/fixture를 먼저 고정하거나, 아직 없는 계약을 Issue의 선행 산출물로 만든다.
5. 외부 원천은 [`api/`](api/)의 조사 문서가 아니라 실제 redacted capture와 provider 약관으로 검증한다.
6. 구현 뒤 migration, contract test, concurrency test, CI 결과를 실행 증거로 연결한다.

### AI 여정 기능을 구현할 때

1. [AI 설계](ai/README.md), [AI guardrail](ai/journey-guardrails.md), [내부 인증](ai/internal-service-authentication.md)을 읽는다.
2. [여정 OpenAPI](contracts/openapi/journey.openapi.yaml)와 SSE fixture/schema를 FE reducer·Spring serializer test에 연결한다.
3. baseline을 먼저 제공한다. RAG는 [corpus sync contract](ai/corpus-sync-contract.md)와 평가·비용·지연 gate를 통과한 뒤에만 활성화한다.

### 운영 또는 공개 write를 열기 전

1. [runtime and reliability](operations/runtime-and-reliability.md)의 backup/restore, alert, scheduler, LKG gate를 확인한다.
2. [moderation](operations/moderation.md)의 operator drill을 실행한다.
3. hosting, secret manager, provider quota/licence, backup destination, 운영 담당자를 실제 배포 설정으로 확정한다.

## 현재 아키텍처 책임 경계

```text
Browser / FE
    | public REST + journey SSE
Spring Boot
    | identity, catalog, discovery, journey, community, run lifecycle
    | tourism ingestion adapters, PostgreSQL/PostGIS ownership
    | private typed request/response
FastAPI
    | AI input policy, provider adapter, optional RAG/corpus ingestion
    | provider SDK only
Gemini or future provider
```

Spring은 비즈니스 상태·공개 응답·외부 관광 API parsing을 소유한다. FastAPI는 provider-specific AI 처리와 선택 RAG를 소유한다. FastAPI는 Spring business schema나 관광 API key를 직접 소유하지 않으며, Spring은 AI provider SDK를 직접 호출하지 않는다.

## 문서 상태를 읽는 규칙

| 상태 | 의미 | 다음 단계 |
|---|---|---|
| `ARCHIVED` | 과거 탐색·전환 기록 | 현재 계약이 아닌 배경 자료로만 사용한다. |
| `Proposed` ADR | 결정 후보와 대안은 기록됐지만 승인되지 않음 | 승인·수정·폐기 여부를 결정한다. |
| `Accepted` ADR | 장기 구조 선택이 확정됨 | 구현·PR·후속 설계가 이 결정을 따른다. |
| Current Design / contract draft | 구현 목표와 검증 기준 | OpenAPI, fixture, migration, test로 실행 가능한 증거를 만든다. |
| Executable Evidence | 실제 코드·migration·test·배포 기록 | 결과와 검증 명령을 연결해 구현됨을 입증한다. |

문서가 서로 충돌할 때의 우선순위는 다음과 같다.

1. `accepted` ADR
2. 현재 책임별 설계·기계 계약 (`architecture/`, `contracts/`, `database/`, `spring/`, `ai/`, `operations/`)
3. `planning/`의 PRD·Issue 후보
4. `report/`, archive, 외부 조사 기록

단, 실행 migration·실제 OpenAPI 생성물·테스트·배포 기록이 생긴 뒤에는 그것이 문서의 구현 여부를 증명한다. 설계 문서만으로 구현 완료를 주장하지 않는다.

## 향후 실행 산출물의 위치

아래 경로는 구현이 시작된 뒤에 만들며, 현재 `docs/spring/`·`docs/ai/`와 구분한다.

```text
spring/                    # Spring Boot source and tests
ai/                        # FastAPI source and tests
db/migration/              # reviewed Flyway migrations
docs/contracts/openapi/    # public API machine contracts
docs/contracts/fixtures/   # FE/BE contract fixtures
docs/ai/evals/             # AI safety and quality evaluation fixtures
docs/operations/runbooks/  # tested operational runbooks
```

## 구현 Issue와 PR의 최소 규칙

각 Issue는 하나의 소유 레이어, 입력/출력 또는 DB 변경 계약, 실패·권한·동시성 처리, 검증 명령, 선행 의존성을 가져야 한다. PR은 관련 Issue를 참조하고, 실제로 merge될 때만 auto-close keyword를 사용한다.

새로운 장기 구조 선택은 [ADR](decisions/README.md)으로 기록한다. endpoint field, fixture 추가, adapter 내부 parsing처럼 이미 승인된 경계 안의 구현 세부사항은 계약 문서와 Issue/PR에 기록한다.
