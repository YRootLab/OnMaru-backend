# 지도와 여정 탐색의 FE REST 계약을 고정한다

> 후속 요청: [행정구역 지도 1.2 변경안](regional-map-and-ingestion.md)이 지역 집계·명시적 후기 조회·커서 종료를 정의한다. 본문의 NEARBY/VIEWPORT·이동 후 자동 요청은 이전1.1안이며 새 지도 구현에 그대로 적용하지 않는다. 연결된 OpenAPI도 아직1.1이다. FE와1.2 OpenAPI 전환 후 계약 동결이 필요하다. 여정1.1은 별개로 유지한다.

2026-09-11 개선안. `/api/v1`, HTTPS JSON, `schemaVersion: "1.1"`. 미배포 v1 검토안의 변경이며 기존 1.0 fixture와 동시에 배포하지 않는다. 필드/enum 변경은 FE decoder와 함께 전환한다. ID는 opaque string; 시간 UTC ISO-8601; 좌표 WGS84, lng=경도/lat=위도다. 지정하지 않은 필드는 생성하지 않는다. NULL과 누락을 혼용하지 않는다.

## 공통 계약

POST/PUT body 최대 16KiB, query trim+NFC 후 1..1000 code points. 인증/소유권은 [identity](data-and-identity.md)를 따른다. 개인 응답은 `Cache-Control: no-store`. 모든 command POST는 `Idempotency-Key`(UUID), unsafe method는 CSRF header 필수다. OAuth redirect는 별도 state flow다. method/path/resource까지 operation scope로 hash한다. 같은 key/payload retry는 최초 응답을 반환하고 hash가 다르면 409. 인증·소유권 재검증 후에만 저장된 응답을 반환한다.

오류 형식은 `{schemaVersion:"1.1",code,message,requestId,details:{...}}`; message는 진단용 plain text이며 FE는 code로 문구를 결정한다. 400 VALIDATION_ERROR/CURSOR_INVALID, 401 AUTH_REQUIRED, 403 CSRF_INVALID, 404 NOT_FOUND, 409 VERSION_CONFLICT/ACTIVE_RUN/PINNED_REF/IDEMPOTENCY_CONFLICT/PROPOSAL_EXPIRED/SAVE_LIMIT, 410 CURSOR_EXPIRED, 413 PAYLOAD_TOO_LARGE, 429 RATE_LIMITED, 503 SERVICE_UNAVAILABLE를 구분한다. 429는 초 단위 Retry-After, details.retryAfterMs를 함께 준다. 알려진 비동기 run 실패는 GET 200 + status FAILED/error이며 GET 자체의 503과 다르다.

## 지도 조회: 범위와 피드의 정렬을 분리한다

지도 게시글은 기존 온기와 별도인 `VisitReview` 장소 방문 짧은 후기다. 전체는 **서버가 보유한 전체 공개 후기**이지 전국 원천 데이터의 완전성을 뜻하지 않는다. 현재 위치 동의는 NEARBY 좌표를 얻는 용도이며 다른 지역 조회 권한을 제한하지 않는다.

`GET /visit-reviews?scope=ALL|REGION|NEARBY|VIEWPORT&limit=20&cursor=...`

| scope | 필수 / 금지 파라미터 | 의미 |
|---|---|---|
| ALL | 공간 파라미터 금지 | 전체 공개 후기 최신순 |
| REGION | regionCode 필수; 좌표/bbox 금지 | canonical 행정구역 내부 후기 |
| NEARBY | lat,lng 필수, radiusMeters 기본 5000, 100..20000; region/bbox 금지 | 사용자가 동의한 위치를 고정 중심으로 반경 조회 |
| VIEWPORT | west,south,east,north 필수; region/중심/radius 금지 | 현재 지도 사각형 내부 후기 |

모든 scope의 정렬은 `createdAt DESC, id DESC`로 고정한다. NEARBY도 반경 필터 뒤 최신순이며 ‘가까운 순’으로 잘못 표시하지 않는다. 거리 정렬은 별도 cursor 계약과 실행 계획 검증 후 추가한다. limit은 1..50. bbox는 west<east, south<north, lng -180..180, lat -90..90; MVP는 날짜변경선 횡단 bbox를 400으로 거절한다. viewport 각 변 geodesic 거리 40km 이하; 초과는 400 VIEWPORT_TOO_LARGE와 details.maxSpanMeters=40000, FE는 확대 안내. 전국 보기에서는 ALL 리스트를 사용하며 모든 핀을 한 번에 내려받지 않는다.

```json
{
  "schemaVersion":"1.1",
  "queryKey":"opaque-scope-hash",
  "items":[{"id":"review_1","placeId":"place_1","placeName":"검수 장소명","lat":37.58,"lng":126.97,"text":"한옥 안쪽 좌석에서 잠시 쉬기 좋았어요.","likeCount":2,"likedByMe":false,"createdAt":"2026-09-11T03:00:00Z","mine":false}],
  "nextCursor":null,
  "hasMore":false,
  "asOf":"2026-09-11T03:01:00Z",
  "coverage":{"status":"SUPPORTED","regionCodes":["SEOUL-JONGNO"]}
}
```

items는 0..limit, nextCursor는 string|null, hasMore는 다음 페이지 유무다. coverage.status는 SUPPORTED/PARTIAL/UNSUPPORTED; 수집한 catalog region 기준이며 후기 수가 0인 것과 미지원 지역을 구분한다. visitorCount는 후기 DTO에서 제외한다. 이는 실시간 방문자 측정이 아니다. 총 건수는 계산하지 않는다.

Cursor는 서명한 version/filterHash/lastCreatedAt/lastId/asOf/expiresAt(10분)을 포함한다. `asOf`는 첫 페이지 DB 시각이며 후속 페이지도 `created_at <= asOf AND (created_at,id)<(lastTime,lastId)`를 적용한다. cursor는 인증 credential이 아니다. filter, limit을 바꾸면 400 CURSOR_INVALID; 변조도 400, 만료는 410. 첫 페이지부터 다시 시작한다. 커서 내부 위치값을 암호화하거나 서버 opaque handle로 보관하여 서명만으로 위치가 숨겨진다고 주장하지 않는다.

삭제/숨김은 매 페이지 현재 권한으로 필터하므로 snapshot isolation을 보장하지 않는다. 늦게 commit된 과거 createdAt 행은 이번 탐색에서 누락될 수 있으며 refresh로 반영한다. MVP는 text edit를 제공하지 않아 정렬 key가 바뀌지 않는다. FE는 id 중복 제거를 한다. 외부 관광 수집은 사용자 후기 createdAt을 과거 값으로 backfill하지 않는다.

## 지도 이동과 FE 갱신

1. 최초 전체/지역/내 인근 선택이 query scope를 결정한다. 위치 거절 시 REGION/ALL 선택을 유지하며 임의 좌표를 사용하지 않는다.
2. 지도 drag/zoom 동안 기존 리스트/핀을 유지한다. idle 이벤트 후 350ms debounce로 VIEWPORT 검색을 한 번 제출한다. 내 인근에서 이동하면 `현재 지도 범위`로 바뀌었다고 표시한다. ‘내 인근’ 재선택 시 사용자 위치 중심으로 돌아간다.
3. 매 새 범위마다 FE requestSequence를 증가시키고 이전 fetch를 AbortController로 취소한다. sequence와 현재 queryKey가 맞는 응답만 적용한다. 취소가 서버 실행 중단을 보장한다고 가정하지 않는다.
4. 첫 page 성공 시 리스트와 **그 페이지에 실린 후기의 핀**을 동시에 교체한다. 이전 범위 결과를 새 범위에 append하지 않는다. 실패 시 기존 결과를 보존하되 ‘이전 범위 결과’ 표시와 재시도를 제공한다.
5. 더 보기는 같은 cursor/query 범위에서만 append한다. 화면에 누적 최대 200개; hasMore가 남으면 ‘범위를 좁혀주세요’. 지도에 보이는 핀이 범위의 전체 후기라고 주장하지 않는다. 전체 밀도/cluster는 후속 별도 endpoint다.
6. 작성 성공 후 현재 범위 첫 page를 다시 요청하고 cursor chain을 버린다. 삭제 성공은 해당 id를 즉시 제거한다. 사용자가 refresh하거나 탭 복귀 후 마지막 조회가 30초 이상 지났을 때 첫 page를 갱신한다. 자동 주기 polling/SSE는 지도 MVP에서 쓰지 않는다.

Public 응답도 mine이 있어 개인화되므로 no-store를 기본으로 한다. 공유 캐시는 mine 없는 DTO로 분리한 후에만 검토한다. 목록/핀의 선택은 같은 reviewId/placeId를 공유한다. 장소별 `GET /places/{id}/visit-reviews`는 동일 cursor 엔진의 place filter adapter이며 새 envelope를 사용한다. 기존 `/places/{id}/warmths` 온기 API는 별개로 유지하고 자동 변환/마이그레이션하지 않는다. 새 후기에는 mood/score/visitorCount를 넣지 않는다.

## 지도 작성과 소유권

| Method/path | 요청 | 응답 / 조건 |
|---|---|---|
| POST /places/{placeId}/visit-reviews | `{text}` | 201 VisitReview + Location; 회원/공개 장소; NFC/trim 후 1..300 code points, CRLF→LF, 개행 최대4개; 태그/별점/사진/댓글 없음 |
| DELETE /visit-reviews/{id} | body 없음 | 204, 작성자만; 동일 본인 삭제 재호출 204, 타인 404 |
| GET /places/{id} | canonical id | 200 기존 CanonicalPlace; 삭제/비공개 404 |

후기는 plain text로 저장/출력한다. HTML 태그를 실행하지 않으며 URL 자동 fetch/미리보기는 없다. 금칙어는 UGC moderation 보조이고 SQL parameterization/출력 escaping/객체 인가를 대신하지 않는다. 정상 지명과 문화 표현 오탐 fixture가 승인되기 전 광범위 금칙어 자동 차단은 켜지 않는다.

## 여정 endpoint와 불변식

| Method/path | 요청 필드 | 성공 |
|---|---|---|
| POST /explorations | query, locale="ko-KR", regionCode? | 202 RunAccepted |
| GET /explorations/{id} | 없음 | 200 ExplorationSnapshot |
| POST /explorations/{id}/turns | clientTurnId UUID, baseVersion int>=0, query, clarificationAnswer? | 202 RunAccepted |
| GET /explorations/{id}/runs/{runId} | 없음 | 200 RunSnapshot, 소유권과 run 소속 확인 |
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

`GET /me/timeline`은 내 정보 화면의 월간 흐름 API다. `month`는 KST 기준 `YYYY-MM`이며 생략하면 현재 월이다. item type은 `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`, 후속 `WROTE_VISIT_REVIEW`다. 서버는 day별 group을 내려주고, 각 item은 `id,type,occurredAt,title,subtitle,thumbnailUrl,target`을 가진다. `target`은 `{type:'PLACE',placeId}` 또는 `{type:'ODII_STORY',storyId,placeId|null}` 또는 `{type:'SAVED_JOURNEY',savedJourneyId}`다. 월간 타임라인은 개인 응답이므로 no-store이며, 다른 회원에게 공유하지 않는다. cursor는 member/month/limit/lastOccurredAt/lastId/asOf/10분 만료를 묶는다.

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
type RunAccepted = {schemaVersion:'1.1';explorationId:string;runId:string;
  stateVersion:number;runUrl:string;snapshotUrl:string;retryAfterMs:number};
type RunSnapshot = {
  schemaVersion:'1.1';runId:string;status:'QUEUED'|'RUNNING'|'COMPLETED'|'FAILED'|'CANCELLED';
  stage:'INTERPRETING'|'RETRIEVING'|'VALIDATING'|'PERSISTING'|null;
  outcome:'INITIAL_BOARD'|'PROPOSAL'|'CLARIFICATION_REQUIRED'|'NO_RESULTS'|null;
  clarification:Clarification|null;retryAfterMs:number;createdAt:string;
  startedAt:string|null;deadlineAt:string;error:{code:string;requestId:string}|null;
};
```

확인 질문은 1..200 code points, choices 0..5개다. allowFreeText=true일 때 자유 답변을 허용한다. answer의 choiceId/text는 정확히 하나만 non-null이고 choiceId는 제공한 선택지여야 한다. 같은 exploration의 최신 완료 확인 질문에만 답할 수 있다. 오래된 ID는409 VERSION_CONFLICT다. 지역 미확정이면 검색 없이 COMPLETED+CLARIFICATION_REQUIRED를 반환한다. 후속 turn은 board=null이어도 새 run을 만든다.

- COMPLETED만 outcome이 필수다. FAILED/CANCELLED는 outcome/clarification=null. QUEUED는 startedAt/stage=null. 모든 terminal은 retryAfterMs=0, FAILED만 error가 필수다.
- Snapshot은 기존 board/pinnedRefs/pendingProposal/recentHistory에 excludedRefs:PlaceRef[], latestRun:RunSnapshot|null, execution:{dataMode,engine,rankingVersion,datasetRevision}를 추가한다. 최초 board/pendingProposal/latestRun은 null이며 후보는 최대3개다.
- Proposal의 orderedRefs:PlaceRef[]는 최종 board의 완전한 순서다. 중복이 없고 kept+added의 집합과 같다. Spring은 해당 순서로 leg를 재계산하며 AI가 이동시간을 생성하지 않는다.
- PIN/UNPIN/EXCLUDE/UNEXCLUDE/APPLY는 실제 상태가 바뀌면 stateVersion+1, no-op은 version 불변이다. EXCLUDE는 pin 상태면409 PINNED_REF이며 UNPIN이 먼저 필요하다. EXCLUDE는 board에서 제거·excluded 추가·leg 재계산을 원자적으로 수행한다. 모두 제외되면 board=null이지만 version은 계속 증가한다. UNEXCLUDE는 검색 허용만 복구하며 board에 자동 추가하지 않는다. excluded는 exploration 수명 동안 최대100개다.
- command는 baseVersion을 반드시 검사한다. active run 중 action/save는409 ACTIVE_RUN이며 먼저cancel할 수 있다. pending proposal 중 pin/exclude 변경은 proposal을 INVALIDATED로 만든다. DISMISS는 version을 바꾸지 않고 pending만 제거한다. APPLY는 version/expiry/pin/exclude/공개 상태를 재검증하고 단일 transaction으로 교체한다.
- board=null에서 후보 생성 성공은 현재 version+1로 확정한다(최초0→1). board가 있는 turn은 proposal을 만들고 version을 유지한다. NO_RESULTS/CLARIFICATION/FAILED/CANCELLED는 기존 board/version을 보존한다. pending 중 새 turn이 승인되면 기존 proposal을 INVALIDATED로 만든다.

## polling과 에러 복구

FE는 RunAccepted 후 run을1초 간격으로 조회하고 background에서는2초로 늦춘다. 이전GET이 진행 중이면 새GET을 겹치지 않는다. terminal이면 polling을 끝내고 snapshot을 읽는다.20초는 서버deadline이며 FE가 FAILED를 확정하지 않는다. deadline 뒤 마지막GET이 실패하면 ‘상태를 확인할 수 없습니다’로 표시하고 재접속 시 run/snapshot을 대조한다. run GET도 기한 초과 terminal화를 시도한다. 기존 FE 독자 AI_TIMEOUT 확정은 폐기한다.

POST 응답 유실은 같은 Idempotency-Key로 재송신한다.409 VERSION_CONFLICT는 snapshot을 다시 읽고 사용자 의도를 확인한다. view/지도/hover는run을 생성하지 않는다. 브라우저 연결 종료는cancel이 아니며 명시cancel만 저장 상태를 변경한다.

## FE traceability와 검증 fixture

| 원래 계약/기능 | 개선 계약 | 검증 fixture |
|---|---|---|
| BE-REQ-004 / MAP-F001 | 기존 places/nearby 유지, 방문 후기 별도 | 장소 목록과 후기 목록을 구분 |
| MAP-F003 관련 새 VR-01 | visit-review scope/cursor | A→B 지도이동에서 늦은A 응답 폐기 |
| 새 VR-02/03, 기존 온기와 별도 | 회원 작성/본인 삭제/좋아요 | mine 위조, 타인삭제404, 중복좋아요1개 |
| JX 질문/제외 | clarification, EXCLUDE | 지역미지정→질문→첫board; pin제외409 |
| JX 저장/재개 | saved resume | 새기기login→saved→resume→turn |
| JX 제안/순서 | version/pin/orderedRefs | A,C유지+B추가→A,B,C; stale apply409 |

기계 판독 계약은 [방문 후기 OpenAPI](visit-reviews.openapi.json)를 함께 제공한다. 여정은 기존 Board 타입과 본문의 변경 필드를 통합한 REST 상세 명세이며, 전체 여정 OpenAPI 생성과 FE/BE fixture 실행은 구현 착수 게이트로 남는다. prose 검토를 runtime contract test 통과로 간주하지 않는다.

## 장소 방문 후기와 좋아요 상세 정책

확정된 사용자 요구는 한옥·한옥 숙박·한옥 카페·전통시장 방문 후 짧은 후기, 좋아요, 대댓글 없음이다. 길이300자/5줄, 댓글 전체 제외는 구현 시작값 제안이다. 작성 대상은 catalog의 검수된 visitReviewEligible=true 장소이며 위 네 종류를 초기 allowlist로 둔다. 방문 인증/GPS 체크인은 요구하지 않으므로 ‘인증 방문’ 배지를 만들지 않는다. 후기 작성자의 실명·provider profile은 노출하지 않고 mine만 표시한다.

| Method/path | 인증/요청 | 응답 |
|---|---|---|
| GET /places/{id}/visit-reviews | 공개,limit/cursor | 동일 ReviewPage, place filter |
| PUT /visit-reviews/{id}/likes/me | 회원+CSRF, body 없음 | 200 `{likedByMe:true,likeCount:integer>=0}` |
| DELETE /visit-reviews/{id}/likes/me | 회원+CSRF | 200 `{likedByMe:false,likeCount:integer>=0}` |

PUT/DELETE는 원하는 상태 설정이므로 toggle endpoint를 사용하지 않는다. key 없이도 DB PK(review_id,member_id)로 멱등이다. 본인 후기 좋아요는403 SELF_LIKE_FORBIDDEN. 숨김/삭제 후기는404. 회원 ACTIVE→후기 row 순서로 잠금 후 INSERT ON CONFLICT DO NOTHING 또는 DELETE, count 조회를 한 transaction에서 수행한다. 좋아요 수는 별도 mutable counter 없이 해당 review PK prefix COUNT로 계산, 응답은 그 transaction의 관측값이다. 삭제는 같은 후기 잠금으로 경합을 직렬화하고 목록에서 즉시 제외한다. 페이지 좋아요 수는 최대50개 review ID만 한 grouped query로 조회하여 N+1을 피한다.

FE는 후기별 좋아요 변경 요청을 직렬화하고 마지막 사용자 의도에 맞는 PUT/DELETE를 보낸다. optimistic UI는 실패 시 이전 값으로 복원하고401이면 로그인 안내한다. 다른 사용자의 좋아요는 다음 GET/refresh 때 반영되며 실시간 동기화를 약속하지 않는다. 좋아요는 v1 지도 최신순이나 여정 ranking에 가중하지 않는다. 추후 인기순은 조작 방지·시간창·cursor 정렬 계약을 별도로 검토한다. 동일 회원이 장소에 여러 후기를 작성하는 것은 허용하지만 작성 rate limit을 적용한다.

## 공간 SQL의 소유권과 실행 기준

Catalog의 `place_identity`에 연결된 현재 published `place_versions`에서 공간 eligibility를 읽는다. Community repository가 catalog table을 직접 수정하지 않으며 Catalog의 공개 read view/port를 사용한다. 장소 좌표가 없는 글은 지도 범위에 포함할 수 없으므로 방문 후기 작성 eligibility는 유효 좌표를 요구한다.

NEARBY는 `ST_DWithin(location, ST_SetSRID(ST_MakePoint(:lng,:lat),4326)::geography, :radiusMeters)`와 geography GiST index를 사용한다. [PostGIS ST_DWithin](https://postgis.net/docs/ST_DWithin.html)의 geography 거리는 미터다. VIEWPORT는 `(location::geometry) && ST_MakeEnvelope(:west,:south,:east,:north,4326)`와 해당 geometry expression GiST를 후보로 둔다. Point bbox 검색에 대해 경계 포함을 테스트한다. [ST_MakeEnvelope](https://postgis.net/docs/ST_MakeEnvelope.html)는 SRID를 가진 사각형을 만든다.

공개 장소 자격/region/bbox filter와 후기 최신순 keyset을 함께 적용한 실행 계획을 10만건·밀집/희소지역에서 비교한다. places 먼저검색 후review join과 review 최신순 후공간filter 중 고정한 하나가 항상 빠르다고 가정하지 않는다. SQL timeout2초를 넘으면503, FE는 기존 결과+재시도 안내를 유지한다. 지도목록 목표p95 500ms는 측정할 목표이며 현재실측값이 아니다.
