# 여정형 AI API 명세: FE 구현·연동 기준

> 상태: 설계 계약 초안, 구현 API 아님
> 기준일: 2026-10-27
> 대상: OnMaru 웹 클라이언트와 Spring 공개 API
> 범위: 대화형 여정 생성·후속 질문·진행 SSE·결과 복구·여정 조작

이 문서는 FE가 여정형 AI 기능을 구현하고 Spring과 연동할 때 필요한 공개 API와 화면 상태 흐름을 한 곳에 모은다. 상세 필드 제약과 기계 판독 스키마는 [Journey OpenAPI](openapi/journey.openapi.yaml)가 정본이며, SSE 프레임은 [SSE JSON Schema 1.3](schemas/journey-sse-event.schema.json) 및 [1.3 fixtures](fixtures/journey-sse-v1.3/)를 따른다. 이 문서의 JSON은 흐름 설명용 예시다.

## 1. FE에 제공되는 기능 경계

브라우저는 Spring 공개 API만 호출한다. FastAPI의 plan/execute, 내부 progress callback, `planHash`, AI 내부 ordinal, 검색기·모델 단계 payload는 공개 계약이 아니다. Spring은 AI 실행을 조정하고, 브라우저에는 현재 여정 진행 단계만 SSE로 전달한다.

| 기능 | FE 계약 |
|---|---|
| 새 여정 생성 | `POST /api/v1/explorations` → `202 RunAccepted` |
| 기존 여정에서 대화 계속하기 | `POST /api/v1/explorations/{explorationId}/turns` → `202 RunAccepted` |
| 진행 상태 보기 | `GET /api/v1/explorations/{explorationId}/runs/{runId}/events` → SSE |
| 현재 run 상태 및 종료 결과 확인 | `GET /api/v1/explorations/{explorationId}/runs/{runId}` → `RunSnapshot` |
| 현재 여정 보드·대화 이력 확인 | `GET /api/v1/explorations/{explorationId}` → `ExplorationSnapshot` |
| 후보 고정·제외·proposal 반영 | `POST /api/v1/explorations/{explorationId}/actions` → 새 `ExplorationSnapshot` |
| 진행 중 요청 취소 | `POST /api/v1/explorations/{explorationId}/runs/{runId}/cancel` → `RunSnapshot` |
| 여정 저장·다시 열기 | `POST/GET/DELETE /api/v1/saved-journeys...` |

초기 탐색과 대화 턴은 비동기 run이다. `202`는 실행이 접수되었다는 뜻이지 결과가 준비되었다는 뜻이 아니다. SSE는 진행 알림 채널이고, 실제 답변·장소·제안의 정본은 GET snapshot이다.

## 2. 사용자 흐름과 FE 상태 전이

```text
새 질문 입력
   │ POST /explorations
   ▼
RunAccepted ── GET run + GET exploration ── 화면 초기화
   │
   ├── EventSource: run.stage ── 진행 표시만 갱신
   │
   ├── reset / terminal / 탭 복귀 ── GET snapshot으로 복원
   │
   ▼
최종 ExplorationSnapshot 렌더링
   │
   ├── 후속 질문 ── POST /turns ── 새 run 구독
   ├── 장소 고정/제외/제안 반영 ── POST /actions ── snapshot 교체
   └── 여정 저장 ── POST /saved-journeys
```

권장 FE 상태는 `IDLE → SUBMITTING → QUEUED/RUNNING → REFRESHING → COMPLETED | FAILED | CANCELLED`다. `CLARIFICATION_REQUIRED`는 run의 `status=COMPLETED`, `outcome=CLARIFICATION_REQUIRED`로 표현되는 완료 결과이며, 사용자가 답변을 입력한 뒤 새 turn을 시작한다. 네트워크 연결 상태와 run 상태는 별도로 관리한다. EventSource가 끊겼다고 run이 실패하거나 취소된 것은 아니다.

### 새 여정 시작

1. FE는 입력을 검증하고 UUID `Idempotency-Key`를 생성한다. 네트워크 timeout으로 동일 요청을 재전송할 때는 같은 key와 같은 body를 재사용한다.
2. `POST /api/v1/explorations`를 호출한다.
3. `202` 응답의 `explorationId`, `runId`, `snapshotUrl`, `eventsUrl`을 저장한다.
4. run snapshot과 exploration snapshot을 GET해 서버 상태를 기준으로 화면을 초기화한다. 이후 EventSource를 연다.
5. `run.stage`는 진행 표시만 갱신한다. terminal, reset, 복원, 재연결 후에는 snapshot을 다시 읽는다.

```http
POST /api/v1/explorations
Idempotency-Key: 2b111111-1111-4111-8111-111111111111
X-CSRF-TOKEN: <csrf-token>
Content-Type: application/json

{"query":"전주에서 조용히 한옥 문화를 둘러볼 수 있는 반나절 여정을 만들어줘","locale":"ko-KR","regionCode":"region-jeonju"}
```

```json
{
  "schemaVersion": "1.3",
  "explorationId": "e3111111-1111-4111-8111-111111111111",
  "runId": "7a111111-1111-4111-8111-111111111111",
  "stateVersion": 0,
  "runUrl": "/api/v1/explorations/e3111111-1111-4111-8111-111111111111/runs/7a111111-1111-4111-8111-111111111111",
  "eventsUrl": "/api/v1/explorations/e3111111-1111-4111-8111-111111111111/runs/7a111111-1111-4111-8111-111111111111/events",
  "snapshotUrl": "/api/v1/explorations/e3111111-1111-4111-8111-111111111111"
}
```

`RunAccepted`의 URL은 API가 돌려준 값을 사용한다. FE가 API path를 재조립하지 않아도 된다. URL은 현재 origin의 상대 경로다.

### 기존 대화 이어가기

각 여정(`explorationId`)은 하나의 대화 컨텍스트와 결과 보드를 가진다. 사용자가 기존 여정에서 질문을 이어가면 새 exploration을 만들지 않고 turn을 추가한다. `baseVersion`은 마지막으로 읽은 `ExplorationSnapshot.stateVersion`이다.

```http
POST /api/v1/explorations/e3111111-1111-4111-8111-111111111111/turns
Idempotency-Key: 5c111111-1111-4111-8111-111111111111
X-CSRF-TOKEN: <csrf-token>
Content-Type: application/json

{"clientTurnId":"6d111111-1111-4111-8111-111111111111","baseVersion":1,"query":"대중교통으로 이동하기 편한 곳을 중심으로 바꿔줘"}
```

clarification 응답에 대한 후속 요청은 `clarificationAnswer`를 함께 보낸다.

```json
{
  "clientTurnId": "6d111111-1111-4111-8111-111111111111",
  "baseVersion": 1,
  "query": "오전 시간대로 부탁해",
  "clarificationAnswer": {
    "clarificationId": "clarification-time-window",
    "choiceId": "morning",
    "text": null
  }
}
```

같은 `Idempotency-Key`와 동일 body 재요청은 최초 응답을 반환한다. 동일 key에 다른 body를 보내면 `409 IDEMPOTENCY_CONFLICT`다. 현재 상태가 요청의 `baseVersion`과 다르면 `409 VERSION_CONFLICT`이며, FE는 exploration snapshot을 다시 읽고 사용자 입력을 보존한 채 충돌을 해소한다. 한 exploration에서 활성 run이 허용되지 않는 상태이면 `409 ACTIVE_RUN`이다.

## 3. 공개 endpoint 요약

모든 endpoint는 `journey.openapi.yaml`의 `/api/v1` 기준이다. 인증은 서비스의 기존 세션 방식에 따른다. 개인 데이터 endpoint는 `Cache-Control: no-store`를 사용한다. 모든 command POST는 UUID `Idempotency-Key`가 필요하고, unsafe method에는 `X-CSRF-TOKEN`이 필요하다.

| Method / path | 용도 | 성공 |
|---|---|---|
| `POST /explorations` | 새 대화형 여정 시작 | `202 RunAccepted` |
| `GET /explorations/{explorationId}` | 보드·refs·최신 run·대화 기록 snapshot | `200 ExplorationSnapshot` |
| `POST /explorations/{explorationId}/turns` | 기존 여정에 질문 추가 | `202 RunAccepted` |
| `POST /explorations/{explorationId}/actions` | 보드 변경 또는 proposal 적용/거절 | `200 ExplorationSnapshot` |
| `GET /explorations/{explorationId}/runs/{runId}` | run 상태 및 최종 상태 조회 | `200 RunSnapshot` |
| `GET /explorations/{explorationId}/runs/{runId}/events` | run 진행 SSE 구독 | `200 text/event-stream` |
| `POST /explorations/{explorationId}/runs/{runId}/cancel` | 활성 run 취소 요청 | `200 RunSnapshot` |
| `POST /saved-journeys` | 현재 exploration 버전 저장 | `201 SavedJourney` 또는 동일 버전 `200` |
| `GET /saved-journeys` | 저장한 여정 목록 | `200 SavedJourneyPage` |
| `GET /saved-journeys/{savedJourneyId}` | 저장 여정 조회 | `200 SavedJourney` |
| `POST /saved-journeys/{savedJourneyId}/resume` | 현재 이용 가능한 리소스로 새 exploration 재개 | `201 ResumeSavedJourneyResponse` |
| `DELETE /saved-journeys/{savedJourneyId}` | 저장 여정 삭제 | `204` |

### 여정 action

`POST /explorations/{explorationId}/actions` body의 `commandId`는 UUID, `baseVersion`은 현재 snapshot version이다. `action`은 다음 중 하나다.

```json
{"commandId":"8e111111-1111-4111-8111-111111111111","baseVersion":2,"action":{"type":"PIN","resourceRef":{"type":"PLACE","id":"place-jeonju-001"}}}
```

지원 action은 `PIN`, `UNPIN`, `EXCLUDE`, `UNEXCLUDE`(PLACE ref)와 `APPLY_PROPOSAL`, `DISMISS_PROPOSAL`(proposalId)다. 성공 응답은 변경 후의 `ExplorationSnapshot`이므로 FE는 로컬 보드를 임의로 합성하지 않고 응답 snapshot으로 교체한다. action 재전송도 같은 idempotency 규칙을 따른다.

### 여정 저장과 다시 열기

저장할 때는 사용자가 확인한 `explorationId`, `baseVersion`, 제목을 보낸다. 저장된 여정은 당시 결과를 다시 볼 수 있고, resume은 저장된 과거 스냅샷을 현재 장소 정보처럼 그대로 사용하지 않고 새 exploration을 만든다. 현재 이용 불가 리소스는 응답의 `unavailableRefs`로 알려준다.

```http
POST /api/v1/saved-journeys
Idempotency-Key: 9f111111-1111-4111-8111-111111111111
X-CSRF-TOKEN: <csrf-token>
Content-Type: application/json

{"explorationId":"e3111111-1111-4111-8111-111111111111","baseVersion":2,"title":"전주 한옥 반나절 산책"}
```

## 4. Snapshot 모델과 렌더링 기준

### `RunSnapshot`

`GET /explorations/{explorationId}/runs/{runId}` 응답은 run 상태의 권위 있는 snapshot이다. 주요 필드는 아래와 같다.

| 필드 | 의미 / FE 처리 |
|---|---|
| `schemaVersion` | 현재 계약 `"1.3"` |
| `status` | `QUEUED`, `RUNNING`, `COMPLETED`, `FAILED`, `CANCELLED` |
| `engine` | `LLM` 또는 `BASELINE`; degraded 실행도 사용자에게 결과가 있으면 정상 결과로 표현 |
| `degradedReason` | quota·timeout·provider 장애 등 제한적 사유. 내부 stack/프롬프트는 아님 |
| `stage` | RUNNING일 때 공개 진행 단계; 다른 상태에서는 `null` |
| `sequence` | Spring이 commit한 이 run의 마지막 public SSE sequence. 초기 `QUEUED=0` |
| `outcome` | 완료 결과 종류: `INITIAL_BOARD`, `PROPOSAL`, `CLARIFICATION_REQUIRED`, `NO_RESULTS` |
| `clarification` | outcome이 `CLARIFICATION_REQUIRED`일 때 표시할 질문 및 선택지 |
| `retryAfterMs` | 재시도 또는 대기 안내가 필요한 경우의 밀리초 값 |
| `createdAt`, `startedAt`, `deadlineAt` | 서버 시각(ISO 8601 date-time). FE에서 서버 deadline을 임의로 연장하지 않음 |
| `error` | 실패 시 공개 오류 object; 표시 문구는 공통 `code`에 따라 FE가 결정 |

예를 들어 진행 중 snapshot은 다음과 같다.

```json
{
  "schemaVersion":"1.3","runId":"7a111111-1111-4111-8111-111111111111",
  "status":"RUNNING","engine":"LLM","degradedReason":null,
  "stage":"SELECTING_EVIDENCE","sequence":3,"outcome":null,
  "clarification":null,"retryAfterMs":0,
  "createdAt":"2026-10-27T10:00:00Z","startedAt":"2026-10-27T10:00:01Z",
  "deadlineAt":"2026-10-27T10:00:20Z","error":null
}
```

### `ExplorationSnapshot`

`GET /explorations/{explorationId}`는 화면을 완전히 복원하는 여정 snapshot이다. `stateVersion`을 action/turn의 `baseVersion`으로 사용한다. `board`는 현재 후보 장소와 제안, 구간, 장소/지역 리소스, 관계 및 evidence ref를 포함한다. `pinnedRefs`, `excludedRefs`, `unavailableRefs`, `execution`, `latestRun`, `pendingProposal`, `recentHistory`, `updatedAt`를 함께 제공한다.

FE는 보드 결과와 기록을 이 snapshot에서 렌더한다. SSE 단계 이벤트는 `board`, 장소, 출처, 답변 내용을 담지 않는다. `latestRun`이 있으면 그 `sequence`가 현재 SSE high-water mark다. 별도의 run snapshot을 조회하는 경우에도 run 종료 때 둘 다 갱신한다.

## 5. SSE 진행 이벤트 계약

### 구독

```js
const source = new EventSource(eventsUrl, { withCredentials: true });
source.addEventListener('run.stage', onRunStage);
source.addEventListener('run.terminal', onRunTerminal);
source.addEventListener('reset', onReset);
source.addEventListener('heartbeat', onHeartbeat);
source.addEventListener('auth_closed', onAuthClosed);
```

브라우저 EventSource의 자동 재연결은 같은 연결 인스턴스에서 마지막 event ID를 `Last-Event-ID`로 전송한다. 별도 헤더가 필요한 인증이더라도 표준 EventSource는 임의 Authorization header를 넣을 수 없으므로, 서비스가 정한 세션 cookie 인증을 사용한다. URL query string에 access token이나 CSRF token을 넣지 않는다.

### 실제 진행 단계

FE 공개 stage는 여섯 개다. 실제 도달한 단계만 발행되며 건너뛴 단계를 FE가 채워 넣지 않는다.

| 공개 stage | FE 권장 문구 |
|---|---|
| `INTERPRETING` | 질문에서 여행 조건을 정리하고 있어요. |
| `RETRIEVING` | 조건에 맞는 장소와 정보를 찾고 있어요. |
| `SELECTING_EVIDENCE` | 관련 정보를 비교해 근거를 고르고 있어요. |
| `GENERATING` | 찾은 정보를 바탕으로 여정을 정리하고 있어요. |
| `VALIDATING` | 장소 정보와 추천 근거를 확인하고 있어요. |
| `PERSISTING` | 여정 결과를 준비하고 있어요. |

단계 진입 알림은 해당 작업의 완료를 의미하지 않는다. 남은 시간, 검색 결과 수, 원문·출처명, 모델 초안을 stage만으로 표시하지 않는다.

```text
id: 3
event: run.stage
data: {"schemaVersion":"1.3","runId":"7a111111-1111-4111-8111-111111111111","sequence":3,"status":"RUNNING","stage":"SELECTING_EVIDENCE"}
```

`id`는 Spring이 발급한 run별 sequence이며 `data.sequence`와 같아야 한다. FastAPI 내부 ordinal과는 별개다. 새로운 public stage 전이, 새 narration delta, terminal event가 commit될 때 sequence가 증가한다. 같은 stage 재전송이나 현재 stage의 새 stream 초기 전송은 기존 sequence를 유지한다.

### terminal / heartbeat / reset / 인증 종료

```text
id: 7
event: run.terminal
data: {"schemaVersion":"1.3","runId":"7a111111-1111-4111-8111-111111111111","sequence":7,"status":"COMPLETED","outcome":"PROPOSAL"}
```

`run.terminal`에는 최종 보드나 답변 본문이 없다. FE는 EventSource를 닫고 run 및 exploration snapshot을 GET한다.

기존 SSE 계약에는 선택적 `run.text.delta` narration event도 정의되어 있다. 이는 최종 답변/보드가 아닌 임시 표시 텍스트이며, delta당 최대 512자·run당 최대 4,000자(Unicode code point 기준)다. FE가 이를 표시한다면 plain text로만 취급하고 장소·지도·run 완료 상태를 갱신하지 않는다. 부분 문구는 reset/실패/최종 snapshot과 함께 폐기하거나 최종 결과와 구분해 표시한다. provider 원문 JSON, 장소 ref, 좌표, prompt, 내부 추론, 오류 원문은 이 event에도 포함하지 않는다. FE telemetry/logging에 text를 기록하지 않는다. 근거는 [기존 REST/SSE 계약](rest-api.md)의 narration 규칙이다.

```text
event: reset
data: {"schemaVersion":"1.3","runId":"7a111111-1111-4111-8111-111111111111"}
```

`reset`은 SSE replay cursor를 이어갈 수 없다는 뜻이다. ID를 포함하지 않으며 현재 run 상태나 실패를 뜻하지 않는다.

```text
: heartbeat
```

전송 계층 heartbeat는 화면 상태 변경이 아니다. 별도 named `heartbeat` JSON event가 제공되더라도 현재 sequence만 재확인하며 sequence를 증가시키지 않는다. FE는 heartbeat로 진행 단계를 바꾸지 않는다.

인증 만료 시 서버가 `auth_closed`로 stream을 닫을 수 있다. 이 경우 기존 인증 갱신 흐름을 실행하고, 인증 복구 뒤 snapshot을 GET한다. run이 계속 활성 상태면 새 EventSource를 연다. SSE 연결 종료를 run cancel로 해석하지 않는다.

## 6. sequence, 재연결 및 snapshot 복구

FE는 run별 `lastAppliedSequence`를 유지한다. 시작값은 받은 `RunSnapshot.sequence`다. 이벤트의 `sequence <= lastAppliedSequence`이면 중복/오래된 알림으로 보고 상태에 적용하지 않는다. 더 큰 sequence의 알림을 처리한 뒤 high-water mark를 갱신한다.

| 상황 | FE 동작 |
|---|---|
| 새 run 접수 | GET run + exploration 후 EventSource open. `QUEUED`는 대기 상태 표시 |
| `run.stage` | sequence 확인 후 진행 문구만 변경 |
| 같은 stream 자동 재연결 | 브라우저 `Last-Event-ID` 사용, replay를 받으며 중복 sequence 무시 |
| `reset` | 해당 EventSource 닫기 → GET run 및 exploration → 화면 복원 → 활성 상태면 오래된 cursor 없이 새 EventSource open |
| `run.terminal` | EventSource 닫기 → GET run 및 exploration → snapshot 결과 렌더 |
| 페이지 새로고침/탭 복귀 | 저장된 explorationId/runId로 GET snapshot 먼저 실행 → 활성 run이면 재구독 |
| stream 오류/timeout | 연결 오류 UI와 run 상태 분리. snapshot을 읽어 계속 진행인지 판단 |
| `COMPLETED`, `FAILED`, `CANCELLED` snapshot | stream 닫기. 최종 상태와 결과는 snapshot 기준 |

snapshot의 sequence보다 작은 이벤트는 stale로 무시한다. `reset` 뒤 재구독은 새 connection을 만들고 오래된 `Last-Event-ID`를 전달하지 않는다. 새 stream이 현재 `RUNNING` stage를 같은 sequence로 보낼 수 있다. 이는 새 진행 전이가 아니다.

terminal event가 누락되어도 snapshot 복구로 결과를 얻을 수 있어야 한다. 따라서 terminal 상태에서 재구독하지 않으며, 서버에서 구독 직후 terminal notification을 받더라도 GET snapshot으로 확정한다.

## 7. 결과, 질문 추가, 취소 및 오류 UX

### 완료 결과

`COMPLETED`이면 `outcome`을 확인한다.

| outcome | FE 처리 |
|---|---|
| `INITIAL_BOARD` | exploration snapshot의 보드와 근거 참조를 표시 |
| `PROPOSAL` | `pendingProposal`을 표시하고 반영/거절 action 제공 |
| `CLARIFICATION_REQUIRED` | `RunClarification.question` 및 선택지를 표시하고 후속 turn 입력 제공 |
| `NO_RESULTS` | snapshot을 기준으로 결과 없음 안내와 검색 조건 수정 입력 제공 |

`engine=BASELINE`이어도 정상 완료일 수 있다. 사용자에게 내부 provider 오류를 그대로 보여주지 않는다. `degradedReason`은 제품 UI에 직접 출력하지 말고, 설계된 fallback 안내가 필요하면 Spring 공개 문구/제품 규칙을 별도로 정한다.

### 취소

활성 run에 대해서만 다음 요청을 보낸다.

```http
POST /api/v1/explorations/e3111111-1111-4111-8111-111111111111/runs/7a111111-1111-4111-8111-111111111111/cancel
Idempotency-Key: a0111111-1111-4111-8111-111111111111
X-CSRF-TOKEN: <csrf-token>
Content-Type: application/json

{}
```

응답 `RunSnapshot`의 `status`가 기준이다. 이미 terminal인 run cancel은 기존 terminal snapshot을 반환한다. 취소 요청 POST timeout 후에는 같은 idempotency key로 재요청하거나 GET run snapshot으로 확정한다. EventSource `close()`는 구독만 닫으며 서버 실행을 취소하지 않는다.

### 오류 처리

공통 오류는 `{schemaVersion,code,message,requestId,details}`를 따른다. 화면 문구와 복구 행동은 `code` 및 HTTP status로 결정하고 `message`를 사용자 문구로 그대로 노출하지 않는다.

| HTTP / code 예 | FE 행동 |
|---|---|
| `400 VALIDATION_ERROR` | 입력 오류 표시, 요청 수정 |
| `401 AUTH_REQUIRED` | 로그인 또는 기존 인증 갱신 흐름 |
| `403 CSRF_INVALID` | CSRF token 갱신 후 사용자가 재시도 |
| `404 NOT_FOUND` | 해당 여정/run 접근 불가 화면 |
| `409 VERSION_CONFLICT` | exploration 재조회, draft 입력 보존 후 재시도 UI |
| `409 ACTIVE_RUN` | 활성 run 조회·복구하고 중복 요청 금지 |
| `409 IDEMPOTENCY_CONFLICT` | key/body 사용 오류; 새 의도라면 새 key 발급 |
| `413 PAYLOAD_TOO_LARGE` / `422` | 요청을 고쳐 입력 단계로 복귀 |
| `429 RATE_LIMITED` | `Retry-After`와 `details.retryAfterMs`에 맞춰 재시도 가능 시각 안내 |
| `503 SERVICE_UNAVAILABLE` | 일시 장애 안내, 같은 요청의 재시도 정책 적용 |
| run snapshot `FAILED` | snapshot error code로 정해진 안내와 재시도 동작 표시 |

AI quota 또는 provider 장애는 후보가 있으면 BASELINE 결과로 완료될 수 있으므로, FE는 HTTP 429/503과 이미 접수된 run의 degraded 완료를 혼동하지 않는다.

## 8. FE 구현 규칙과 미지원 가정

- 최종 결과 렌더링의 단일 기준은 GET `ExplorationSnapshot`과 `RunSnapshot`이다. SSE payload로 결과 보드를 조립하지 않는다.
- 질문 제출은 optimistic UI를 허용하되 임시 사용자 입력과 서버 `recentHistory`를 구별하고, snapshot 수신 후 정규화한다.
- `baseVersion`은 마지막 snapshot 값으로 설정한다. 여러 action/turn 요청을 동시에 보내지 않고, 활성 run 중 추가 turn 가능 여부는 서버 응답을 기준으로 처리한다.
- EventSource URL은 응답의 `eventsUrl`을 사용한다. 로그인 세션 cookie를 전달하고 query parameter에 비밀값을 넣지 않는다.
- schemaVersion 1.2와 1.3을 수용하는 parser를 새 stage 활성화 전에 배포한다. 알 수 없는 stage는 일반 진행 문구로 처리하며 SSE 소비를 중단하지 않는다.
- stage는 실제 도달한 단계만 온다. FE는 누락 단계를 오류로 처리하지 않으며 고정 단계 애니메이션을 완료된 것처럼 표시하지 않는다.
- SSE가 끊겨도 사용자 질문, 현재 exploration, run 상태를 즉시 삭제하지 않는다. snapshot 재조회로 복구한다.
- 접근 권한은 서버가 매 endpoint에서 검증한다. FE에서 explorationId/runId를 숨기는 것은 인가가 아니다.
- 오류 telemetry에는 query 원문, 답변 본문, 출처 원문, 인증정보를 기록하지 않는다.

## 9. 배포 호환 순서와 계약 검증

1. FE가 v1.2와 v1.3 schemaVersion, 새 `SELECTING_EVIDENCE`·`GENERATING` stage 및 알 수 없는 enum fallback을 처리한다.
2. Spring이 OpenAPI 1.3 snapshot과 SSE 계약을 배포하되 새 stage 발행은 호환 확인 후 활성화한다.
3. Spring/FastAPI 내부 진행 callback은 공개 SSE와 독립적으로 검증한다. 내부 callback 세부는 [Spring 통합 PRD](../ai-rag-spring/backend-integration-prd.md)와 [FastAPI progress callback 계약](../ai-rag-spring/fastapi-progress-callback.md)에 둔다.
4. FE·BE contract test가 OpenAPI 예제, SSE schema, reset/reconnect/terminal fixture를 검증한다.

BE 구현 전 합의할 핵심 acceptance:

- POST 시작/turn의 `202` 및 idempotency 재전송이 같은 run으로 복구된다.
- `RunAccepted`의 URL로 snapshot 및 SSE를 구독할 수 있다.
- 공개 stage는 정의된 여섯 값만 사용하며 실제 수행 단계만 전달된다.
- 각 public SSE event ID는 data sequence와 일치하며 run snapshot의 sequence와 모순되지 않는다.
- duplicate, out-of-order, reset, terminal, reconnect, tab restore 시 최종 화면이 snapshot으로 수렴한다.
- FE는 질문 후속 턴·action·취소·저장·재개 후 서버 snapshot을 정본으로 표시한다.

## 10. 관련 정본과 fixture

- [Journey OpenAPI 1.3](openapi/journey.openapi.yaml)
- [Public REST API 계약](rest-api.md#여정-endpoint와-불변식)
- [SSE JSON Schema 1.3](schemas/journey-sse-event.schema.json)
- [SSE 1.2 호환 schema](schemas/journey-sse-event.v1.2.schema.json)
- [SSE fixture index](fixtures/journey-sse-fixtures.json), [1.3 frames](fixtures/journey-sse-v1.3/)
- [여정 fixture](fixtures/journey/)
- [기존 FE handoff](frontend-handoff.md#여정-진행-sse-13-fe-확정-계약)
- [Spring AI/RAG 통합 PRD](../ai-rag-spring/backend-integration-prd.md)
