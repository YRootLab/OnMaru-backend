# FastAPI 진행 상태 수신과 여정 SSE 중계 계약

> 상태: 구현 전 계약 초안. 이 문서는 Spring과 AI Engine 사이의 진행 callback, Spring의 공개 여정 run/SSE 매핑만 정한다. 코드와 UI 구현은 포함하지 않는다. 기존 private REST plan/execute는 유지하며, gRPC는 이 경로를 구현·계측한 다음 별도 측정으로 판단한다.

## 현재 계약과 충돌하는 점

### AI Engine 초안 대조 상태

요청에서 지정한 `OnMaru-AI-engine/docs/ai-rag/progress-event-contract.md`는 현재 로컬 `../onmaru-ai-engine` clone의 어떤 branch에서도 찾지 못했고, 원격 `main`의 동일 경로도 404로 확인됐다. 따라서 AI Engine 초안과 BE 문서의 직접 비교는 아직 불가능하다. 이 파일이 다른 branch/commit에 있으면 그 ref를 공유해 주면 cross-repo diff가 가능하다.

확인 가능한 이전 BE progress 초안과 이번 요청을 비교하면 REST callback 유지, run/planHash 검증, stage 목록과 매핑, 내부 ordinal 멱등성 및 gRPC 후속 측정 방향은 일치한다. 이전 초안에 명시되지 않았던 GET 복구 cursor를 보완하기 위해 `RunSnapshot.sequence`를 1.3 공개 계약에 추가했다. 이 BE OpenAPI와 SSE schema가 브라우저 API의 정본이다.

| 현재 문서 계약 | 충돌 | 변경 제안 |
|---|---|---|
| `internal-ai.yaml`의 proposal 요청은 `internal.ai.v1`, `additionalProperties: false`이며 `planHash`와 callback grant가 없다. | 승인된 plan binding과 callback 자격을 추가하면 v1 요청의 닫힌 schema와 호환되지 않는다. | 실행 요청을 `internal.ai.v2`로 올리고 필수 `planHash`와 선택적 `X-Run-Progress-Token`을 추가한다. 기존 corpus/error wire schema는 각자 버전을 유지한다. |
| `journey.openapi.yaml` RunAccepted/RunSnapshot/ExplorationSnapshot과 SSE schema는 1.2이며 stage는 `INTERPRETING`, `RETRIEVING`, `VALIDATING`, `PERSISTING`이다. | `SELECTING_EVIDENCE`, `GENERATING`을 1.2 closed enum에 넣으면 엄격한 기존 client가 알 수 없는 값을 받는다. | 여정 run/SSE만 1.3으로 버전 올린다. 1.2 schema와 fixture를 보존하고 호환 rollout 순서를 적용한다. |
| Spring↔FastAPI 문서는 고정 plan/execute에 private REST를 쓰고 gRPC는 병목 확인 후 unary 비교로 둔다. | 내부 단계 진행을 Spring으로 되돌리는 계약이 정의되어 있지 않다. | 동일한 private REST 경계에 제한된 callback endpoint를 추가한다. callback은 실행결과·검색 전략·최종 결과를 바꾸지 않는다. |
| SSE 문서는 Spring이 sequence와 snapshot/reconnect 복구를 소유한다. | FastAPI ordinal을 공개 sequence로 재사용하면 run별 sequence 및 복구의 소유권이 흐려진다. | FastAPI ordinal은 내부 순서/deduplication 전용으로 두고, public event sequence는 Spring이 stage·text delta·terminal event를 commit할 때 할당한다. |

## 소유권과 요청 흐름

```text
Browser ── REST command ──> Spring Journey API
                               │
                               ├── private REST plan/execute ──> FastAPI
                               │       └── X-Run-Progress-Token (callback 전용)
                               │
                               <── private POST progress callback ──┘
                               │
                               ├── 활성 run/planHash/ordinal 검증
                               ├── run snapshot의 stage 갱신 및 Spring SSE sequence 할당
                               └── run.stage ── SSE ──> Browser

Browser reconnect ── GET run snapshot ──> Spring (정답 상태)
```

Spring은 run 상태, 승인된 planHash, public stage, SSE sequence, replay 및 snapshot을 소유한다. FastAPI는 AI 실행 중 실제 진입한 큰 단계의 상태만 전달한다. FastAPI callback ordinal은 public API나 SSE payload로 전달하지 않는다. 최종 run 결과는 항상 Spring의 GET snapshot이 기준이다.

## 내부 REST 계약

### 실행 요청과 callback 주소

- 기존 Spring → FastAPI private REST 실행 요청과 응답 흐름을 유지한다. OpenAPI의 현재 `/journey/proposals`는 구현 전 제안 endpoint 이름이므로 실제 plan/execute 명칭은 기존 계약과 함께 유지하고, 별도 transport나 MCP로 바꾸지 않는다.
- 실행 요청의 schema는 `internal.ai.v2`로 올리고, FastAPI가 실행할 승인 계획을 가리키는 `planHash`를 필수로 보낸다. 형식은 `sha256:<64 lowercase hex>`다.
- Spring은 progress callback을 허용할 때 `X-Run-Progress-Token`을 실행 요청에 붙인다. 토큰이 없으면 FastAPI는 callback을 시도하지 않는다.
- FastAPI callback: `POST /internal/v1/ai/runs/{runId}/progress` (private network/TLS only).

### 이벤트 본문

```json
{
  "schemaVersion": "internal.ai.progress.v1",
  "runId": "c1d2e3f4-a5b6-7c8d-9e0f-1a2b3c4d5e6f",
  "planHash": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "ordinal": 1,
  "stage": "PREPARING_INPUT"
}
```

필수 필드는 위 다섯 개뿐이다. 이벤트 schema는 `additionalProperties: false`로 하고 document/source 이름, 검색 본문, 사용자 원문, 생성 초안, prompt, 내부 추론 및 후보 결과를 거부한다. Trace context는 선택적 전송 header로만 전달하며 payload에 넣지 않는다.

`stage` 허용값과 순서는 아래와 같다.

| 순서 | FastAPI 내부 stage | 의미 |
|---:|---|---|
| 1 | `PREPARING_INPUT` | 승인된 실행 입력을 정리하고 검색 입력을 준비하기 시작함 |
| 2 | `RETRIEVING` | 선택된 검색 경로 중 하나 이상을 실행하기 시작함 |
| 3 | `SELECTING_EVIDENCE` | 검색 후보를 합치거나 재정렬하고 답변 근거 후보를 선택하기 시작함 |
| 4 | `GENERATING` | 선택된 근거를 기반으로 답변 생성을 시작함 |
| 5 | `CHECKING_OUTPUT` | 생성 결과의 정책·근거 검사를 시작함 |

ordinal은 한 AI 실행 내에서 1부터 증가하는 정수다. 실제 진입한 단계만 보낸다. 경로가 검색이나 생성을 실행하지 않으면 해당 단계를 만들지 않는다. 단계는 위 순서를 거꾸로 이동하지 않으며 건너뛸 수 있다. 동일 stage 진입 이벤트는 불필요하게 반복하지 않는다.

### Callback 인증과 권한

Spring은 해당 run을 시작할 때 실행 토큰과 별개로 progress 전용 capability JWT를 발급한다. 예시 claim:

| Claim | 규칙 |
|---|---|
| `iss` | 환경별 Spring issuer |
| `sub` | `onmaru-ai-engine` 서비스 principal |
| `aud` | `spring-ai-progress` 정확히 일치 |
| `scope` | `journey.run.progress.write` 하나만 허용 |
| `run_id` | callback 대상 run UUID 하나 |
| `plan_hash` | 승인된 해당 plan의 hash |
| `iat`, `exp` | 유효기간은 짧게 제한하고 run deadline을 넘지 않음. 기본 상한은 60초 |
| `jti` | grant 식별자. callback별 one-use nonce로 취급하지 않음 |

기존 service bearer는 `jti`를 한 번만 사용할 수 있지만 progress grant는 하나의 실행에서 여러 callback을 보내야 하므로 그 정책을 재사용하지 않는다. Progress grant는 run/plan/scope/audience를 검증하고, 이벤트 멱등성은 ordinal로 처리한다. callback 토큰은 한 run의 진행 상태 외 다른 endpoint, MCP tool, corpus, 품질 조회 권한을 주지 않는다. 토큰과 서명은 log/trace/metric에 기록하지 않는다.

Spring은 signature/issuer/audience/expiry/scope를 확인한 뒤 path `runId`와 body `runId`, grant `run_id`, 현재 활성 run, 현재 승인 planHash, event `planHash`가 모두 일치하는지 확인한다. mismatch/권한 부족은 거부한다. plan 변경·run 재시작 때 이전 grant를 사용할 수 없다.

### 수신과 멱등 처리

Spring은 현재 run 상태와 마지막 callback ordinal을 원자적으로 비교·갱신한다. 마지막 ordinal은 Spring 내부 영속 run 상태 또는 동등한 내구성 있는 projection에 보관하며 public snapshot에는 노출하지 않는다.

| 입력 상황 | 처리 계약 |
|---|---|
| 현재 활성 run + 승인 planHash 일치 + 새 ordinal + 순서가 앞으로 진행 | 내부 ordinal 저장, public stage로 매핑, 상태 변경이 있을 때 Spring SSE sequence 증가 후 `run.stage` 발행 |
| 같은 ordinal과 완전히 같은 event 재전송 | 성공으로 멱등 응답. snapshot/stage/sequence를 다시 변경하지 않음 |
| 같은 ordinal인데 stage/hash가 다름 | 충돌로 거부; 상태를 변경하지 않음 |
| 마지막 ordinal보다 작은 ordinal | stale event로 무시; 상태를 되돌리지 않고 sequence를 증가시키지 않음 |
| run이 terminal이거나 취소/만료/비활성 상태 | 무시; terminal snapshot과 sequence를 유지 |
| ordinal은 증가했으나 stage 순서가 이전으로 이동 | 거부 또는 stale로 무시; 상태 rollback 금지 |
| run/plan/token 불일치 | 거부 및 비식별 보안 관측 기록; 상태 변경 없음 |

새 stage 전이는 database compare-and-set/transaction 경계 안에서 처리해 callback 중복 또는 동시 도착이 stage/sequence를 역행시키지 않도록 한다. 똑같은 public stage가 이미 snapshot에 있으면 duplicate `run.stage`를 발행하지 않는다. FastAPI의 callback HTTP 실패·timeout은 AI 작업의 성공/실패를 결정하지 않는다. FastAPI는 제한된 callback 시도 후 진행 알림을 생략하고 실제 AI 실행을 계속한다. Spring 최종 응답과 검증은 callback 상태를 신뢰하지 않고 기존 계약으로 독립 검증한다.

## 공개 SSE stage mapping

| FastAPI 내부 stage | Spring `run.stage` | 후속 Spring 단계 |
|---|---|---|
| `PREPARING_INPUT` | `INTERPRETING` | — |
| `RETRIEVING` | `RETRIEVING` | — |
| `SELECTING_EVIDENCE` | `SELECTING_EVIDENCE` | — |
| `GENERATING` | `GENERATING` | — |
| `CHECKING_OUTPUT` | `VALIDATING` | Spring final public validation도 `VALIDATING` |
| — | — | 저장 작업 진입 시 `PERSISTING` |

Spring은 FastAPI callback 이후 공개 근거·장소 공개/철회 상태와 결과 schema를 최종 검증한다. callback의 `CHECKING_OUTPUT`은 그 검증 완료를 의미하지 않는다. 최종 Spring 검증이 끝나기 전 `VALIDATING`을 완료로 표현하지 않는다. 저장 이후 Spring이 terminal state를 확정한다.

SSE event envelope는 기존 `{schemaVersion,runId,status,stage,sequence}` 형식을 유지한다. FastAPI ordinal, planHash 및 capability 정보는 공개 이벤트에 포함하지 않는다. Spring이 새 public event를 commit할 때 run별 sequence를 할당하고 snapshot의 `RunSnapshot.sequence` 및 replay buffer high-water와 같은 전이에서 관리한다. sequence는 새 stage, text delta, terminal 때 증가하고 heartbeat/reset 때는 증가하지 않는다. 재연결/공백/reset은 Spring이 복구하며 client는 GET run/exploration snapshot으로 복구한다.

## 버전과 rollout 호환 규칙

### 내부 Spring ↔ FastAPI

- 실행 요청에 필드가 추가되므로 proposal/execute request/response contract는 `internal.ai.v2`로 전환한다. v1의 strict schema를 조용히 확장하지 않는다.
- 기존 계약은 [`internal-ai.v1.yaml`](../contracts/legacy/internal-ai.v1.yaml)에 보존하고 최신 endpoint/schema는 [`internal-ai.yaml`](../contracts/openapi/internal-ai.yaml)에 둔다.
- 진행 callback body는 실행 요청 schema와 별도 lifecycle인 `internal.ai.progress.v1`이다. callback endpoint가 배포되어 있어도 Spring이 token을 발급하지 않으면 기존 실행은 진행 callback 없이 동작한다.
- AI Engine은 v2 요청을 받은 경우에만 v2 기능을 사용한다. 양쪽이 v2를 지원하기 전에 Spring은 v2 요청을 보내지 않는다. 동시 이중 지원 기간에는 명시된 v1/v2 compatibility test를 둔다.

### 공개 Spring ↔ browser SSE/snapshot

- run 전용 `RunAccepted`, `RunSnapshot`, `ExplorationSnapshot.latestRun`과 SSE event data를 `1.3`으로 올린다. unrelated map/review/saved journey schemaVersion은 이 변경으로 일괄 변경하지 않는다.
- `journey.openapi.yaml` draft는 `1.3.0-draft`로 올린다. SSE JSON Schema와 fixture는 최신 파일을 1.3으로 갱신하고, 기존 1.2 schema/fixture는 versioned 파일로 보존한다.
- 1.2 consumer가 두 새 enum을 알지 못하므로 backend callback 수신과 stage 발행은 기본 비활성 상태로 배포한다. consumer가 1.3을 수용할 수 있도록 업데이트된 뒤 feature gate를 켠다. 이 문서 작업에서는 UI/client 변경을 수행하지 않는다.
- 혼합 rollout 중에는 1.2 consumer에 새 값이 포함된 snapshot/SSE를 보내지 않는다. 현재 endpoint가 명시적 contract negotiation을 제공하지 않으므로, dual-version serving이 필요해지면 별도 API negotiation 설계가 선행되어야 한다.
- `GET /explorations/{id}/runs/{runId}`와 exploration GET snapshot이 최종 상태의 권위 데이터다. SSE replay/reset은 전달 최적화이며 snapshot 계약을 대체하지 않는다.
- 1.3 `RunSnapshot.sequence`는 snapshot과 같은 commit에서 저장한 마지막 public SSE sequence다. FE가 reset 뒤 stale EventSource를 닫고 snapshot을 읽은 후 새 EventSource를 열 수 있도록 1.3 contract에 포함한다.
- 현재 `scripts/test/validate-journey-contract.py`의 invariant 샘플은 RunSnapshot `schemaVersion: 1.2`를 고정 가정하고 신규 `sequence`도 제공하지 않는다. v1.3 계약을 구현할 때 이 검증기와 함께 유지할 v1.2 호환 fixture를 갱신해야 한다. 이번 문서 작업에서는 검증 코드와 UI를 수정하지 않는다.

## Contract fixture 계획

추가·갱신할 계약 자산:

1. `internal-ai.yaml`: v2 실행 요청의 필수 planHash, callback token header, private progress endpoint, `internal.ai.progress.v1` strict schema.
2. `journey.openapi.yaml`: run 응답의 stage enum과 schemaVersion 1.3, `ExplorationSnapshot` 내 latestRun.
3. `journey-sse-event.schema.json`: 1.3 event schema; `journey-sse-event.v1.2.schema.json`은 기존 허용 stage만 유지.
4. `journey-sse-fixtures.json` 및 `journey-sse-v1.3/`: v1.3 stage mapping, skipped stage, duplicate/stale/conflicting ordinal, terminal callback, callback delivery failure, reconnect snapshot, v1.2 rollout gate와 기계 검증 가능한 각 stage/reset/terminal frame. 이전 1.2 fixture는 versioned copy에 유지.
5. 내부 progress fixture: valid event, missing/extra field, bad schemaVersion, wrong path/body runId, token wrong scope/audience/expiry, grant run/plan mismatch, accepted ordinal, same-event replay, conflicting same ordinal, stale ordinal, stage regression, terminal run, callback unavailable.

각 fixture는 다음 결과를 검증해야 한다: 상태 전이는 승인 run에만 발생, stage·ordinal rollback 없음, 중복 public sequence 없음, terminal 고정, 사용자/검색 데이터 비노출, GET snapshot과 SSE의 일치, callback 장애가 AI outcome에 영향을 주지 않음. 실제 serializer, transaction, SSE 재연결 테스트는 구현 PR의 완료 기준이며 이 문서 변경만으로 동작 검증된 것으로 간주하지 않는다.

## 구현 시 수용 기준

- OpenAPI와 fixture가 event field/enum/인증/version 규칙과 일치한다.
- grant를 다른 run 또는 planHash에 재사용할 수 없고, service token과 권한이 분리되어 있다.
- ordinal 비교·snapshot 갱신·Spring sequence 할당이 원자적이고 중복·역순 callback에도 rollback이 없다.
- callback이 실제 진입 stage만 만들며 FastAPI ordinal은 public SSE ID/sequence에 나타나지 않는다.
- FastAPI progress HTTP timeout/5xx를 주입해도 실제 AI 실행결과는 callback 독립으로 결정된다.
- SSE 재연결·reset·terminal 이후 GET snapshot이 항상 최종 기준이고 OpenTelemetry trace는 서비스 경계를 연결하되 민감 payload/token을 기록하지 않는다.
- public 1.3 stage emission은 client compatibility gate 통과 전 활성화되지 않는다.

## 관련 계약

- [Internal AI OpenAPI](../contracts/openapi/internal-ai.yaml)
- [Journey OpenAPI](../contracts/openapi/journey.openapi.yaml)
- [현재 journey SSE schema](../contracts/schemas/journey-sse-event.schema.json) · [1.2 schema](../contracts/schemas/journey-sse-event.v1.2.schema.json)
- [현재 journey SSE fixtures](../contracts/fixtures/journey-sse-fixtures.json) · [1.2 fixtures](../contracts/fixtures/journey-sse-fixtures.v1.2.json)
- [Spring↔FastAPI 경계와 transport 결정](journey-query-mcp.md)
- [Spring backend integration PRD](backend-integration-prd.md)
