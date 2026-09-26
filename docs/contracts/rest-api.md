# Public REST API contract

2026-09-12 구현 전 계약. `/api/v1`, HTTPS JSON, `schemaVersion: "1.2"`를 현재 지도/후기 계약으로 고정한다. 여정은 REST command와 SSE 알림을 함께 사용한다. 아직 배포된 API가 아니며, 계약·scaffold Issue는 먼저 발행할 수 있지만 각 기능 구현은 해당 OpenAPI와 FE fixture가 동결된 뒤 시작한다.

## 공통 계약

POST/PUT body 최대 16KiB, query trim+NFC 후 1..1000 code points. 인증/소유권은 [identity](../spring/identity-and-journey.md)를 따른다. 개인 응답은 `Cache-Control: no-store`. 모든 command POST는 `Idempotency-Key`(UUID), unsafe method는 CSRF header 필수다. OAuth redirect는 별도 state flow다. method/path/resource까지 operation scope로 hash한다. 같은 key/payload retry는 최초 응답을 반환하고 hash가 다르면 409. 인증·소유권 재검증 후에만 저장된 응답을 반환한다.

오류 형식은 `{schemaVersion:"1.2",code,message,requestId,details:{...}}`; message는 진단용 plain text이며 FE는 code로 문구를 결정한다. 400 VALIDATION_ERROR/CURSOR_INVALID, 401 AUTH_REQUIRED, 403 CSRF_INVALID, 404 NOT_FOUND, 409 VERSION_CONFLICT/ACTIVE_RUN/PINNED_REF/IDEMPOTENCY_CONFLICT/PROPOSAL_EXPIRED/SAVE_LIMIT, 410 CURSOR_EXPIRED, 413 PAYLOAD_TOO_LARGE, 422 JOURNEY_SCOPE_UNSUPPORTED/PRIVACY_REDACT_REQUIRED/SAFETY_BLOCKED, 429 RATE_LIMITED, 503 SERVICE_UNAVAILABLE를 구분한다. 일일 AI quota가 없거나 provider가 실패해도 후보가 있으면 `AI_QUOTA_EXCEEDED`는 내부 degraded reason으로만 기록하고 같은 run을 BASELINE으로 완료한다. 422 입력 거절은 exploration run이나 원문 turn을 만들지 않는다. 429는 초 단위 Retry-After, details.retryAfterMs를 함께 준다. 알려진 비동기 run 실패는 SSE terminal event와 GET snapshot 모두에서 같은 error code를 보이며, SSE 연결 자체 실패는 GET 실패와 구분한다. 여정 AI의 intake와 provider 경계는 [AI guardrail·adapter harness](../ai/journey-guardrails.md)를 따른다.

## 한옥 수결첩: 로그인 회원의 위치 체크인

수결 정의는 `GET /stamps`에서 공개 조회하고, 개인 획득 상태는 로그인 후 `GET /me/stamp-book`에서만 조회한다. `POST /places/{placeId}/check-ins`는 세션, CSRF, UUID `Idempotency-Key`, 브라우저 위치가 모두 필요하다. 새 체크인은 201, 같은 회원·장소·UTC 15분 구간의 반복 체크인은 200이며 기존 row를 반환한다.

서버는 active Catalog의 한옥 계열 장소에 대해서만 PostGIS 거리를 계산한다. 위치 정확도는 100m 이하여야 하며 성공 조건은 `distanceMeters - accuracyMeters <= 200`이다. 요청한 위도·경도 원문은 DB나 응답에 남기지 않는다. 개인 응답은 `no-store`이고, KST 하루 성공 체크인은 회원당 30회로 제한한다. 422는 `LOCATION_ACCURACY_TOO_LOW` 또는 `OUTSIDE_CHECK_IN_RADIUS`, 429는 `CHECK_IN_RATE_LIMITED`, 의존 서비스 장애는 503 `SERVICE_UNAVAILABLE`다. 기계 판독 계약과 상세 DTO는 [한옥 수결첩 OpenAPI](openapi/hanok-stamps.openapi.yaml)를 기준으로 한다.

## 지도와 후기: 1.2 단일 계약

지도 게시글은 `VisitReview` 장소 방문 짧은 후기다. FE의 `visited`, “지도 게시글”, “온기 후기”는 모두 같은 VisitReview 모델을 뜻하며 별도 리소스가 아니다. 현재 지도에서는 행정구역 집계를 탐색하고, 사용자가 지역을 명시적으로 선택한 뒤에만 후기 본문을 읽는다. 지도 drag/zoom, 반경, viewport는 서버 본문 조회를 만들지 않는다.

| GET | 요청 | 응답 |
|---|---|---|
| `/visit-review-regions` | `parentRegionCode?` | 시·도 또는 선택 부모의 시·군·구 집계, `regionRevision`, `countsAsOf`, `unassignedCount` |
| `/regions/resolve` | `lat,lng` | 위치 동의 후의 후보 행정구역. 응답은 `no-store` |
| `/visit-reviews` | `scope=ALL|REGION`, `regionCode`는 REGION에서만 필수, `limit`, `cursor?` | `schemaVersion:"1.2"` ReviewPage |
| `/places/{id}/visit-reviews` | `limit`, `cursor?` | 장소 필터가 고정된 동일 ReviewPage |

`NEARBY`, `VIEWPORT`, `radiusMeters`, bbox 파라미터는 1.2에서 지원하지 않으며 전달되면 400이다. ALL은 전체 공개 후기 최신순, REGION은 하나의 canonical 행정구역 최신순이다. 목록 정렬은 `createdAt DESC,id DESC`, limit은 1..50이다. cursor는 version/filterHash/limit/lastCreatedAt/lastId/asOf/regionRevision/expiresAt을 scope에 묶고 10분 뒤 만료한다. 새 filter/region/limit은 첫 페이지부터 시작한다.

FE는 집계 선택 전 기존 결과를 유지하고, `이 지역 후기 보기` 성공 후에만 목록·핀을 교체한다. 지도 이동은 UI 표현이며 데이터 요청이 아니다. 후기 작성·삭제 뒤에는 현재 지역 첫 페이지와 부모 집계만 무효화한다. 지도에는 SSE와 주기 polling을 사용하지 않는다.

## 지도 작성과 소유권

| Method/path | 요청 | 응답 / 조건 |
|---|---|---|
| POST /places/{placeId}/visit-reviews | `{text,mood?,score?,tags?}` | 201 VisitReview + Location; 회원/공개 장소; text는 NFC/trim 후 1..300 code points, CRLF→LF, 개행 최대4개; mood는 북적/한적, score는 1..5, tags는 최대 5개·각 20 code points; 사진/댓글 없음 |
| DELETE /visit-reviews/{id} | body 없음 | 204, 작성자만; 동일 본인 삭제 재호출 204, 타인 404 |
| POST /visit-reviews/{id}/reports | `{reason,detail?}` | 202; 회원, 본인 후기는 403, 같은 회원의 열린 신고는 200 기존 상태 |
| GET /places/{id} | canonical id | 200 기존 CanonicalPlace; 삭제/비공개 404 |

후기는 기본 `PUBLISHED`로 게시한다. 신고와 고신뢰 PII/위협 탐지로 `HIDDEN`이 될 수 있으며, 운영자 판정만 `PUBLISHED`, `HIDDEN`, `REMOVED`를 전환한다. 신고 수만으로 자동 숨김하지 않는다. 모든 판정은 actor, reason, 이전/이후 상태, 시각을 audit record로 남기며 일반 회원에게 moderation detail을 공개하지 않는다. HTML 실행·URL fetch는 하지 않는다. 금칙어는 보조 신호이며 SQL parameterization/출력 escaping/객체 인가를 대신하지 않는다.

## 여정 endpoint와 불변식

| Method/path | 요청 필드 | 성공 |
|---|---|---|
| POST /explorations | query, locale="ko-KR", regionCode? | 202 RunAccepted |
| GET /explorations/{id} | 없음 | 200 ExplorationSnapshot |
| POST /explorations/{id}/turns | clientTurnId UUID, baseVersion int>=0, query, clarificationAnswer? | 202 RunAccepted |
| GET /explorations/{id}/runs/{runId} | 없음 | 200 RunSnapshot, 소유권과 run 소속 확인 |
| GET /explorations/{id}/runs/{runId}/events | `Accept: text/event-stream`, `Last-Event-ID?` | SSE progress/terminal notification; 소유권과 run 소속 확인 |
| POST /explorations/{id}/runs/{runId}/cancel | `{}` | 200 RunSnapshot; terminal이면 기존 terminal 응답 |
| POST /explorations/{id}/actions | commandId UUID, baseVersion, action | 200 ExplorationSnapshot |
| POST /saved-journeys | explorationId, baseVersion, title(1..80) | 201 SavedJourney; 동일 source version 기존 저장은 200 |
| GET /saved-journeys | limit 1..50 default20,cursor? | 200 `{items:[SavedJourneySummary],nextCursor,hasMore}`; savedAt DESC,id DESC |
| GET /saved-journeys/{id} | 없음 | 200 SavedJourney; 읽기 전용 |
| POST /saved-journeys/{id}/resume | `{}` | 201 `{exploration:ExplorationSnapshot,unavailableRefs:PlaceRef[]}` |
| DELETE /saved-journeys/{id} | 없음 | 204 본인만 |
| PUT /saved-resources/places/{placeId} | body 없음 | 200 `{resourceType:"PLACE",resourceId,savedByMe:true,savedAt}`; 회원+공개 장소 |
| DELETE /saved-resources/places/{placeId} | body 없음 | 204; 회원, 없어도 성공 |
| PUT /saved-resources/odii-stories/{storyId} | body 없음 | 200 `{resourceType:"ODII_STORY",resourceId,savedByMe:true,savedAt}`; 회원+공개 오디 |
| DELETE /saved-resources/odii-stories/{storyId} | body 없음 | 204; 회원, 없어도 성공 |
| GET /saved-resources | type=PLACE 또는 ODII_STORY, limit 1..50 default20,cursor? | 200 `{items:[SavedResourceSummary],nextCursor,hasMore}`; savedAt DESC,id DESC |
| GET /me/timeline | month=YYYY-MM, limit 1..50 default20,cursor? | 200 `{month,groups,nextCursor,hasMore,unavailableCount}`; occurredAt DESC,id DESC |
| GET /members/me | 없음 | 200 `{id,displayName:null}`; 401 비회원 |
| GET /auth/csrf | 없음 | 200 `{token,headerName:"X-CSRF-TOKEN"}` + guest cookie 필요 시 |
| GET /auth/kakao/login | returnTo=/discover, explorationId? | 302 Kakao; 소유권 확인 후 state 발급 |
| GET /auth/kakao/callback | code,state 또는 OAuth error | 303 /discover?auth=success\|failed; 실패 시 기존 guest board 유지 |
| POST /auth/logout | `{}` | 204 현재 세션 폐기, guest snapshot 삭제 안 함 |
| DELETE /members/me | 없음 | 202 `{status:"DELETING"}`; token 즉시 폐기 |

Saved 목록 cursor는 member ID/limit/lastSavedAt/lastId/asOf/10분 만료를 묶는다. saved에는 pending proposal·원문 turn·guest token·사용자 위치를 넣지 않는다. 로그인 성공 자체로 자동 저장하지 않고 FE가 유지한 저장 intent로 POST한다.

SavedResource 목록 cursor도 member ID/type/limit/lastSavedAt/lastId/asOf/10분 만료를 묶는다. `type` 생략으로 장소와 오디를 섞어 반환하지 않는다. `PLACE` item은 현재 공개 place projection으로 `placeId,name,category,regionName,thumbnailUrl|null,savedByMe:true`를 hydrate한다. `ODII_STORY` item은 현재 공개 오디 projection으로 `storyId,spotId,title,placeId|null,durationSeconds|null,savedByMe:true`를 hydrate한다. 비회원은 저장 상태를 서버에 쓰지 않으며 FE가 임시 intent를 보존한 뒤 로그인 후 같은 PUT을 다시 보낸다. 장소 카드와 오디 카드 public DTO는 로그인 회원일 때 `savedByMe:boolean`을 포함한다. 익명 응답은 `savedByMe:false`로 내려도 되지만, localStorage 값을 서버 truth처럼 신뢰하지 않는다.

### `PLACE`는 한옥 화면과 지도가 공유하는 관광 장소 찜이다

`PLACE`는 한옥 화면만의 별도 북마크도, 관광공사 원본의 `contentId`를 저장하는 기능도 아니다. Spring catalog가 공개한 **canonical `placeId` 하나**를 회원이 저장하는 공통 기능이다. 따라서 한옥·한옥 숙박·한옥 카페·한옥 체험·전통시장과, 지도에 공개되는 일반 관광지가 같은 PUT/DELETE endpoint와 같은 `savedByMe` 상태를 사용한다. 최초 허용 category와 공급자 분류의 실제 매핑은 provider fixture에서 검증하며, 공개되지 않았거나 매핑이 불명확한 source row는 저장 대상이 아니다.

한옥 목록/상세와 지도 장소 카드의 로그인 회원 응답에는 `savedByMe`를 포함한다. 비회원은 `false`를 받을 수 있으나, 하트 클릭은 서버 저장이 아니라 로그인 후 재실행할 intent일 뿐이다. Spring은 `PUT /saved-resources/places/{placeId}`에서 현재 공개 상태를 다시 검증하고, 같은 회원·장소의 반복 PUT은 같은 saved state를 반환한다. 이미 해제된 장소의 반복 DELETE도 성공한다.

Odii는 장소를 발견하게 하는 콘텐츠다. `audio_place_odii_links`의 검수된 연결이 있는 Odii spot/story 화면은 연결된 canonical 장소 카드를 보여 주고, 그 카드도 `PLACE`로 저장한다. 이야기를 나중에 다시 재생하려는 사용자를 위한 `ODII_STORY` 저장은 보조 기능으로 유지하되, Odii 화면의 기본 찜 경험은 연결된 관광 장소를 저장하는 것이다. 원본 Odii ID나 KTO 원본 ID를 browser가 저장·비교하는 API는 제공하지 않는다.

`GET /me/timeline`은 내 정보 화면의 월간 흐름 API다. `month`는 KST 기준 `YYYY-MM`이며 생략하면 현재 월이다. item type은 `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`, 후속 `WROTE_VISIT_REVIEW`다. 서버는 day별 group을 내려주고, 각 item은 `id,type,occurredAt,title,subtitle,thumbnailUrl,target`을 가진다. `SAVED_PLACE`의 `title`은 현재 공개 장소명, `subtitle`은 사람이 읽을 수 있는 장소 category·지역, `occurredAt`은 사용자가 찜한 시각이다. FE는 이를 예컨대 “9월 13일에 전주 한옥마을을 찜했어요”처럼 렌더한다. `target`은 `{type:'PLACE',placeId}` 또는 `{type:'ODII_STORY',storyId,placeId|null}` 또는 `{type:'SAVED_JOURNEY',savedJourneyId}`다. 월간 타임라인은 개인 응답이므로 no-store이며, 다른 회원에게 공유하지 않는다. cursor는 member/month/limit/lastOccurredAt/lastId/asOf/10분 만료를 묶는다.

## 빠진 public DTO와 상태 전이를 보완한다

기존 seven-day handoff의 Candidate/Board/JourneyLeg/RelationView 형식은 유지하며 아래 타입을 우선 적용한다. 모든 응답의 schemaVersion은 1.1이다.

```ts
type ResourceRef = { type: 'PLACE'|'REGION'|'TOPIC'; id: string };
type PlaceRef = {type:'PLACE';id:string};
type Action =
  | {type:'PIN'|'UNPIN'|'EXCLUDE'|'UNEXCLUDE';resourceRef:PlaceRef}
  | {type:'APPLY_PROPOSAL'|'DISMISS_PROPOSAL';proposalId:string};
type Clarification = {
  id:string; reason:'REGION_MISSING'|'REGION_AMBIGUOUS'|'UNSUPPORTED_CONDITION';
  question:string; choices:{id:string;label:string;regionCode:string|null}[];
  allowFreeText:boolean;
};
type ClarificationAnswer = {clarificationId:string;choiceId:string|null;text:string|null};
type RunAccepted = {schemaVersion:'1.2';explorationId:string;runId:string;
  stateVersion:number;runUrl:string;eventsUrl:string;snapshotUrl:string};
type RunSnapshot = {
  schemaVersion:'1.2';runId:string;status:'QUEUED'|'RUNNING'|'COMPLETED'|'FAILED'|'CANCELLED';
  engine:'LLM'|'BASELINE'; degradedReason:'AI_QUOTA_EXCEEDED'|'AI_TIMEOUT'|'AI_INVALID_RESPONSE'|'AI_SERVICE_UNAVAILABLE'|null;
  stage:'INTERPRETING'|'RETRIEVING'|'VALIDATING'|'PERSISTING'|null;
  outcome:'INITIAL_BOARD'|'PROPOSAL'|'CLARIFICATION_REQUIRED'|'NO_RESULTS'|null;
  clarification:Clarification|null;retryAfterMs:number;createdAt:string;
  startedAt:string|null;deadlineAt:string;error:{code:string;requestId:string}|null;
};
```

확인 질문은 1..200 code points, choices 0..5개다. allowFreeText=true일 때 자유 답변을 허용한다. answer의 choiceId/text는 정확히 하나만 non-null이고 choiceId는 제공한 선택지여야 한다. 같은 exploration의 최신 완료 확인 질문에만 답할 수 있다. 오래된 ID는409 VERSION_CONFLICT다. 지역 미확정이면 검색 없이 COMPLETED+CLARIFICATION_REQUIRED를 반환한다. 후속 turn은 board=null이어도 새 run을 만든다.

- COMPLETED만 outcome이 필수다. FAILED/CANCELLED는 outcome/clarification=null. QUEUED는 startedAt/stage=null. 모든 terminal은 retryAfterMs=0, FAILED만 error가 필수다. `degradedReason`은 `engine=BASELINE`이고 LLM을 요청했으나 quota/provider 결과를 쓰지 못했을 때만 non-null이며, FE는 이를 raw 장애 문구가 아닌 기본 탐색 결과 표시로 쓴다.
- Snapshot은 기존 board/pinnedRefs/pendingProposal/recentHistory에 excludedRefs:PlaceRef[], latestRun:RunSnapshot|null, execution:{dataMode,engine,rankingVersion,datasetRevision}를 추가한다. 최초 board/pendingProposal/latestRun은 null이며 후보는 최대3개다.
- Proposal의 orderedRefs:PlaceRef[]는 최종 board의 완전한 순서다. 중복이 없고 kept+added의 집합과 같다. Spring은 해당 순서로 leg를 재계산하며 AI가 이동시간을 생성하지 않는다.
- PIN/UNPIN/EXCLUDE/UNEXCLUDE/APPLY는 실제 상태가 바뀌면 stateVersion+1, no-op은 version 불변이다. EXCLUDE는 pin 상태면409 PINNED_REF이며 UNPIN이 먼저 필요하다. EXCLUDE는 board에서 제거·excluded 추가·leg 재계산을 원자적으로 수행한다. 모두 제외되면 board=null이지만 version은 계속 증가한다. UNEXCLUDE는 검색 허용만 복구하며 board에 자동 추가하지 않는다. excluded는 exploration 수명 동안 최대100개다.
- command는 baseVersion을 반드시 검사한다. active run 중 action/save는409 ACTIVE_RUN이며 먼저cancel할 수 있다. pending proposal 중 pin/exclude 변경은 proposal을 INVALIDATED로 만든다. DISMISS는 version을 바꾸지 않고 pending만 제거한다. APPLY는 version/expiry/pin/exclude/공개 상태를 재검증하고 단일 transaction으로 교체한다.
- board=null에서 후보 생성 성공은 현재 version+1로 확정한다(최초0→1). board가 있는 turn은 proposal을 만들고 version을 유지한다. NO_RESULTS/CLARIFICATION/FAILED/CANCELLED는 기존 board/version을 보존한다. pending 중 새 turn이 승인되면 기존 proposal을 INVALIDATED로 만든다.

## SSE 진행 알림과 snapshot 복구

SSE는 여정 run의 UX 채널이며 상태의 정답은 PostgreSQL run/snapshot이다. `events` endpoint는 `Cache-Control: no-store`, `Content-Type: text/event-stream`, 15초 heartbeat를 사용하고 다음 이벤트만 전송한다.

| event | data | 의미 |
|---|---|---|
| `run.stage` | `runId,status,stage,sequence` | QUEUED/RUNNING 및 해석·후보·제안·검증 진행 |
| `run.terminal` | `runId,status,outcome,errorCode?,sequence` | COMPLETED/FAILED/CANCELLED 알림. 결과 본문은 포함하지 않음 |
| `heartbeat` | `runId,sequence` | 연결 유지. 업무 상태 변경이 아님 |
| `reset` | `runId` | replay buffer에 없는 `Last-Event-ID` 또는 서버 재시작. 즉시 GET 재동기화 |

event ID는 run별 단조 증가 sequence다. 서버는 연결 시 현재 상태를 `run.stage`로 한 번 보낸 뒤, 메모리 replay buffer에 있는 event만 `Last-Event-ID` 이후로 재전송한다. 버퍼 밖 공백, deploy/restart, 401/404/410, network close에서는 FE가 지수 backoff로 한 번 재연결하고 `GET /runs/{runId}`와 `GET /explorations/{id}`를 읽어 정답을 다시 맞춘다. terminal 수신 뒤에도 snapshot을 GET으로 읽어 board를 렌더한다.

SSE 연결 종료는 cancel이 아니며, cancel은 명시 command만 상태를 바꾼다. 20초 deadline과 sweeper는 SSE 연결 유무와 무관하다. EventSource가 쓸 수 없는 환경에서는 1초 간격 GET polling을 **호환성 fallback**으로만 사용한다. POST 응답 유실은 같은 `Idempotency-Key`로 재송신한다.

## FE traceability와 검증 fixture

| 원래 계약/기능 | 개선 계약 | 검증 fixture |
|---|---|---|
| BE-REQ-004 / MAP-F001 | 기존 places/nearby 유지, 방문 후기 별도 | 장소 목록과 후기 목록을 구분 |
| MAP-F003 관련 새 VR-01 | region 집계/명시적 목록/cursor | 지도 이동은 본문 요청0, 지역 확정 뒤 목록 교체 |
| 새 VR-02/03, 기존 온기와 별도 | 회원 작성/본인 삭제/좋아요 | mine 위조, 타인삭제404, 중복좋아요1개 |
| BE-REQ-010 관광 장소 찜 | canonical `placeId` PUT/DELETE, `savedByMe`, 월간 `SAVED_PLACE` | 한옥·지도·Odii 연결 카드가 같은 ID를 저장하고 로그인 intent·비공개 처리를 검증 |
| JX 질문/제외 | clarification, EXCLUDE | 지역미지정→질문→첫board; pin제외409 |
| JX 저장/재개 | saved resume | 새기기login→saved→resume→turn |
| JX 제안/순서/SSE | version/pin/orderedRefs/events | A,C유지+B추가→A,B,C; replay 공백→GET 재동기화 |

기계 판독 계약은 [방문 후기 OpenAPI](openapi/visit-reviews.openapi.json), [여정 OpenAPI](openapi/journey.openapi.yaml), [SSE parsed-frame JSON Schema](schemas/journey-sse-event.schema.json), [reconnect/reset fixture 목록](fixtures/journey-sse-fixtures.json)을 함께 제공한다. 구현 전에는 이 fixture를 FE reducer와 Spring serializer contract test로 실행한다. prose 검토를 runtime contract test 통과로 간주하지 않는다.

## 장소 방문 후기와 좋아요 상세 정책

확정된 사용자 요구는 한옥·한옥 숙박·한옥 카페·한옥 체험·전통시장 방문 후 짧은 후기, 좋아요, 대댓글 없음이다. 길이300자/5줄, 댓글 전체 제외는 구현 시작값 제안이다. 작성 대상은 catalog의 검수된 visitReviewEligible=true 장소이며 위 다섯 종류를 초기 allowlist로 둔다. 방문 인증/GPS 체크인은 요구하지 않으므로 ‘인증 방문’ 배지를 만들지 않는다. 후기 작성자의 실명·provider profile은 노출하지 않고 mine만 표시한다.

| Method/path | 인증/요청 | 응답 |
|---|---|---|
| GET /places/{id}/visit-reviews | 공개,limit/cursor | 동일 ReviewPage, place filter |
| PUT /visit-reviews/{id}/likes/me | 회원+CSRF, body 없음 | 200 `{likedByMe:true,likeCount:integer>=0}` |
| DELETE /visit-reviews/{id}/likes/me | 회원+CSRF | 200 `{likedByMe:false,likeCount:integer>=0}` |

PUT/DELETE는 원하는 상태 설정이므로 toggle endpoint를 사용하지 않는다. key 없이도 DB PK(review_id,member_id)로 멱등이다. 본인 후기 좋아요는403 SELF_LIKE_FORBIDDEN. 숨김/삭제 후기는404. 회원 ACTIVE→후기 row 순서로 잠금 후 INSERT ON CONFLICT DO NOTHING 또는 DELETE, count 조회를 한 transaction에서 수행한다. 좋아요 수는 별도 mutable counter 없이 해당 review PK prefix COUNT로 계산, 응답은 그 transaction의 관측값이다. 삭제는 같은 후기 잠금으로 경합을 직렬화하고 목록에서 즉시 제외한다. 페이지 좋아요 수는 최대50개 review ID만 한 grouped query로 조회하여 N+1을 피한다.

FE는 후기별 좋아요 변경 요청을 직렬화하고 마지막 사용자 의도에 맞는 PUT/DELETE를 보낸다. optimistic UI는 실패 시 이전 값으로 복원하고401이면 로그인 안내한다. 다른 사용자의 좋아요는 다음 GET/refresh 때 반영되며 실시간 동기화를 약속하지 않는다. 좋아요는 v1 지도 최신순이나 여정 ranking에 가중하지 않는다. 추후 인기순은 조작 방지·시간창·cursor 정렬 계약을 별도로 검토한다. 동일 회원이 장소에 여러 후기를 작성하는 것은 허용하지만 작성 rate limit을 적용한다.

## 공간 SQL의 소유권과 실행 기준

Catalog의 `place_identity`에 연결된 현재 published `place_versions`에서 공간 eligibility를 읽는다. Community repository가 catalog table을 직접 수정하지 않으며 Catalog의 공개 read view/port를 사용한다. 장소 좌표가 없는 글은 지도 범위에 포함할 수 없으므로 방문 후기 작성 eligibility는 유효 좌표를 요구한다.

REGION 목록은 published place의 canonical `region_id`와 공개 후기의 `created_at DESC,id DESC` keyset을 함께 적용한다. 위치 동의는 `/regions/resolve`의 경계 조회에만 사용하며, 후기 본문 SQL에 반경·bbox predicate를 넣지 않는다. 전국 ALL과 밀집/희소 REGION의 실행 계획을 10만건 fixture에서 비교한다. 부모 집계는 공개 leaf region GROUP BY로 계산하며, 필요할 때만 별도 projection을 검토한다. SQL timeout2초를 넘으면503, FE는 기존 결과+재시도 안내를 유지한다. 지도목록 목표p95 500ms는 측정할 목표이며 현재실측값이 아니다.
