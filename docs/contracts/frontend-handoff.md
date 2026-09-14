# Frontend handoff: backend contract changes

2026-09-12 기준. 이 문서는 기존 FE에 없거나 기존 FE 계약과 달라진 기능을 FE 작업자가 한 번에 볼 수 있도록 정리한 전달서다. 실제 구현 완료나 배포 완료를 의미하지 않는다. `docs/specs` 원본은 외부 symlink이므로 이 저장소에서 직접 수정하지 않았다.

최신 설계 기준은 다음 순서로 읽는다.

1. 지도/지역/수집 변경은 [인증 확장·원천 수집·행정구역 지도](../spring/catalog-ingestion.md)의 1.2 제안이 우선한다.
2. 여정 탐색, 저장, 공통 오류, 기존 VisitReview 상세는 [FE REST 계약](rest-api.md)을 따른다.
3. 회원·소유권·보존 정책은 [인증·영속 모델·보존](../spring/identity-and-journey.md)을 따른다.

## FE가 새로 알아야 하는 큰 변화

| 구분 | 기존 FE에 없거나 달라진 점 | FE 영향 |
|---|---|---|
| 관광 장소 찜 | 한옥·한옥 숙박·카페·체험·전통시장·지도 관광지를 같은 canonical `placeId`로 찜한다. | 한옥 목록/상세, 지도 장소 카드, Odii 연결 장소 카드가 하나의 `savedByMe` 상태와 찜 액션을 공유한다. |
| 내 월간 타임라인 | 어떤 장소를 언제 찜했고 어떤 여정을 저장했는지 월별 흐름으로 보여준다. | 단순 컬렉션보다 “9월에 저장한 한옥·카페·관광지”의 날짜·장소·지역·카테고리를 우선 보여준다. |
| 지도 후기 | 기존 온기와 별도인 짧은 방문 후기 `VisitReview`를 사용한다. | 온기 mood/score UI를 재사용하지 않는다. 좋아요는 있지만 대댓글은 없다. |
| 지도 조회 방식 1.2 | 자동 viewport 본문 조회보다 행정구역 집계→지역 선택→명시적 목록 조회를 우선한다. | 지도 이동만으로 리스트를 갈아끼우지 않는다. 사용자가 지역을 선택하고 후기 보기를 눌러 목록을 읽는다. |
| 커서 페이징 | page/pageIndex/offset/totalPages를 쓰지 않는다. | `items`, `hasMore`, `nextCursor`만 본다. 배열 인덱스는 0부터이며 화면 순번은 FE 전용이다. |
| 인증 확장 | MVP는 Kakao지만 내부 회원 ID는 provider 독립 UUID다. | FE는 provider subject/email을 사용자 ID로 저장하거나 비교하지 않는다. |
| 여정 run 상태 | REST command + SSE 진행 알림 + snapshot 복구가 현재 MVP 통신이다. | `run.stage`/`run.terminal`을 표시하고, reconnect·`reset`·탭 복귀에는 GET run/snapshot으로 재동기화한다. |
| AI 이용 한도와 장애 | 비회원 2회, 로그인 회원 5회/KST 일의 임시 Gemini 여정 한도를 둔다. | quota 소진·Gemini/FastAPI 실패 뒤에도 이미 해석한 후보가 있으면 같은 run을 `engine=BASELINE`으로 완료한다. FE는 raw 오류 대신 기본 탐색 결과를 표시한다. |

## 새 API: 모든 관광 장소를 하나의 찜으로 저장하기

찜은 사용자가 마음에 드는 **관광 장소**를 개인 라이브러리에 저장하는 기능이다. `saved_journeys`와 다르다. 여정 저장은 여행길 전체 snapshot이고, 장소 찜은 현재 공개 projection을 다시 읽는 참조 저장이다.

여기서 `PLACE`는 한옥 화면만의 리소스가 아니다. 관광공사 국문 API를 수집·정규화해 OnMaru catalog에 공개한 canonical 장소 하나를 뜻한다. 한옥, 한옥 숙박, 한옥 카페, 한옥 체험, 전통시장, 지도에 노출되는 일반 관광지는 모두 같은 `placeId`를 쓴다. FE는 관광공사 `contentId`를 저장하거나 비교하지 않는다.

이 선택 덕분에 한옥 상세에서 찜한 장소가 지도 카드에서도 찜 상태로 보인다. 한 화면에서 찜했다고 다른 화면에서 별도로 다시 저장할 필요가 없다. 장소의 운영정보·사진·원본 데이터 행을 각각 찜하지 않는다. 사용자가 저장하는 대상은 “전주 한옥마을” 같은 장소 자체다.

| Method/path | 인증 | 요청 | 성공 응답 |
|---|---|---|---|
| `PUT /api/v1/saved-resources/places/{placeId}` | 회원+CSRF | body 없음 | `200 {resourceType:"PLACE",resourceId,savedByMe:true,savedAt}` |
| `DELETE /api/v1/saved-resources/places/{placeId}` | 회원+CSRF | body 없음 | `204`, 이미 없어도 성공 |
| `PUT /api/v1/saved-resources/odii-stories/{storyId}` | 회원+CSRF | body 없음 | `200 {resourceType:"ODII_STORY",resourceId,savedByMe:true,savedAt}` |
| `DELETE /api/v1/saved-resources/odii-stories/{storyId}` | 회원+CSRF | body 없음 | `204`, 이미 없어도 성공 |
| `GET /api/v1/saved-resources?type=PLACE&limit=20&cursor=...` | 회원 | query | 장소 담아두기 목록 |
| `GET /api/v1/saved-resources?type=ODII_STORY&limit=20&cursor=...` | 회원 | query | 오디 이야기 담아두기 목록 |

`type` 생략으로 장소와 오디를 섞어 반환하지 않는다. 목록 정렬은 `savedAt DESC,id DESC`다. `limit`은 1..50, 기본 20이다. 커서는 member/type/limit/asOf/lastSavedAt/lastId에 묶이고 10분 뒤 만료된다.

### 화면별 찜 동작

| 화면 | FE가 보여 주는 대상 | 저장 대상 | 사용자의 결과 |
|---|---|---|---|
| 한옥 목록·한옥 상세 | 공개 canonical 한옥 장소 | `PLACE + placeId` | 내 정보의 해당 날짜에 한옥 찜 기록이 생긴다. |
| 지도 | 한옥·숙소·카페·체험·시장·일반 관광지 카드 | `PLACE + placeId` | 지도와 한옥 화면의 동일 장소가 모두 찜 상태가 된다. |
| Odii spot/story | 검수된 연결 장소 카드 | `PLACE + placeId` | 오디를 통해 발견한 실제 관광 장소를 나중에 다시 찾는다. |
| Odii 이야기 | 오디오 재생 정보 | `ODII_STORY + storyId`의 보조 저장 | 장소 찜과 별개로 다시 듣기 목적일 때 사용할 수 있다. |

회원은 카드 또는 상세 상단의 하트 버튼으로 `PUT`/`DELETE`를 호출한다. 비회원은 로그인 안내를 받고 FE가 `placeId`와 원하는 상태만 짧게 기억한다. 로그인 성공 후 같은 PUT을 한 번 실행한다. 로그인 취소·실패 시 서버 저장은 만들지 않는다. 로그인 회원의 장소 응답은 `savedByMe`를 기준으로 렌더하며, localStorage 값을 서버 상태처럼 다시 렌더하지 않는다.

장소가 비공개·삭제·권리 변경으로 내려가면 backend는 목록 hydrate에서 제외한다. FE는 과거 카드 정보를 계속 보여 주거나 다른 장소로 대체하지 않는다. 월간 타임라인에서는 `unavailableCount`만 보여 줄 수 있다.

장소 목록 item은 현재 공개 place projection에서 hydrate한다.

```ts
type SavedPlaceSummary = {
  resourceType: 'PLACE';
  resourceId: string;
  placeId: string;
  name: string;
  category: string;
  regionName: string | null;
  thumbnailUrl: string | null;
  savedByMe: true;
  savedAt: string;
};
```

오디 목록 item은 현재 공개 오디 projection에서 hydrate한다.

```ts
type SavedOdiiStorySummary = {
  resourceType: 'ODII_STORY';
  resourceId: string;
  storyId: string;
  spotId: string;
  title: string;
  placeId: string | null;
  durationSeconds: number | null;
  savedByMe: true;
  savedAt: string;
};
```

`SavedPlaceSummary`의 `category`와 `regionName`은 “한옥 카페 · 전주”처럼 사용자가 자신이 찜한 이유를 알아볼 수 있게 표시한다. 원천 상세 field 전체를 snapshot으로 복사하지 않으므로, 이름·대표 이미지·공개 정보는 현재 catalog projection을 다시 읽는다.

Odii story의 `placeId`가 존재하더라도 자동으로 장소를 찜하지 않는다. 사용자가 연결 장소 카드의 찜 버튼을 눌렀을 때만 `PLACE` 저장을 만든다. 이야기의 다시 듣기 저장은 별도 `ODII_STORY` 버튼으로 제공할 수 있다. 비회원은 서버에 담아두기를 쓰지 않고, FE가 임시 intent를 기억했다가 로그인 후 같은 PUT을 다시 보낸다. 로그인하지 않은 public 카드 응답은 `savedByMe:false`로 표시할 수 있지만 localStorage 상태를 서버 정답처럼 쓰지 않는다.

## 새 API: 내 월간 타임라인

내 정보 화면은 단순히 장소/오디/여정을 각각 나열하는 컬렉션보다, 사용자가 월별로 어떤 장소를 언제 찜했고 어떤 여정을 저장했는지 보여주는 타임라인을 우선한다. 이 기능은 “한옥 페이지·지도·Odii에서 발견한 후보를 찜하고 나중에 다시 이어 보는 흐름”을 보여주는 개인 화면이다.

| Method/path | 인증 | 요청 | 성공 응답 |
|---|---|---|---|
| `GET /api/v1/me/timeline?month=2026-09&limit=20&cursor=...` | 회원 | month 선택, limit/cursor | `{month,groups,nextCursor,hasMore,unavailableCount}` |

`month`는 KST 기준 `YYYY-MM`이다. 생략하면 현재 월이다. 정렬은 `occurredAt DESC,id DESC`이고, paging은 기존 cursor 규칙을 따른다. 응답은 개인 정보이므로 `Cache-Control: no-store`다.

```ts
type MemberTimeline = {
  month: string; // YYYY-MM, KST 기준
  groups: TimelineDayGroup[];
  nextCursor: string | null;
  hasMore: boolean;
  unavailableCount: number;
};

type TimelineDayGroup = {
  date: string; // YYYY-MM-DD
  items: TimelineItem[];
};

type TimelineItem = {
  id: string;
  type: 'SAVED_PLACE' | 'SAVED_ODII_STORY' | 'SAVED_JOURNEY' | 'WROTE_VISIT_REVIEW';
  occurredAt: string;
  title: string;
  subtitle: string | null;
  thumbnailUrl: string | null;
  target:
    | { type: 'PLACE'; placeId: string }
    | { type: 'ODII_STORY'; storyId: string; placeId: string | null }
    | { type: 'SAVED_JOURNEY'; savedJourneyId: string }
    | { type: 'VISIT_REVIEW'; reviewId: string; placeId: string };
};
```

초기 타임라인 item은 `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`다. `SAVED_PLACE`는 title=장소명, subtitle=장소 카테고리·지역, occurredAt=찜한 시각으로 표시한다. 예를 들어 FE는 “9월 13일 · 전주 한옥마을 · 한옥 · 전주”처럼 날짜와 맥락을 함께 보인다. `WROTE_VISIT_REVIEW`는 VisitReview 작성 기능 구현 뒤 추가한다. FE는 아직 지원하지 않는 item type을 받으면 해당 item을 숨기고 telemetry 또는 개발 로그로 남긴다.

FE 화면 흐름은 다음을 권장한다.

1. 내 정보 첫 화면 상단에 현재 월 타임라인을 보여준다.
2. 월 선택기는 `이번 달`, `지난 달`, 직접 월 선택을 제공한다.
3. 같은 날짜의 여러 활동은 하나의 day group으로 묶는다.
4. 장소 item 클릭은 현재 공개 장소 상세로 이동한다. 한옥·카페·숙소·일반 관광지 모두 같은 흐름이다.
5. 오디 item 클릭은 오디 story 또는 연결된 장소 상세로 이동한다.
6. 저장 여정 item 클릭은 saved journey 상세 또는 resume 동작으로 이어진다.
7. 비공개/삭제된 resource는 item으로 노출하지 않고 월 단위 `unavailableCount`만 표시할 수 있다.
8. 타임라인에서 항목을 지우는 별도 API는 만들지 않는다. 장소/오디는 담아두기 해제, 여정은 saved journey 삭제를 호출한다.

## 새 API: 행정구역 지도 집계 1.2

기존 `VIEWPORT` 자동 조회안은 1.1 초안이며 사용하지 않는다. 새 지도 구현은 1.2 행정구역 계약으로 전환한다. 1.2 OpenAPI 초안은 작성됐고, FE/BE fixture는 구현 Issue의 선행 산출물이다.

| Method/path | 요청 | 성공 응답 |
|---|---|---|
| `GET /api/v1/visit-review-regions` | parentRegionCode 생략 | 시·도별 후기 수 집계 |
| `GET /api/v1/visit-review-regions?parentRegionCode={sido}` | 시·도 코드 | 시·군·구별 후기 수 집계 |
| `GET /api/v1/regions/resolve?lat={lat}&lng={lng}` | 사용자 동의 좌표 | 좌표가 속한 후보 행정구역 |
| `GET /api/v1/visit-reviews?scope=REGION&regionCode={code}&limit=20&cursor=...` | 선택 지역 | 해당 지역 후기 목록 |
| `GET /api/v1/visit-reviews?scope=ALL&limit=20&cursor=...` | 전체 | 전체 공개 후기 최신순 |

`NEARBY`, `VIEWPORT`, `radius`, `bbox`, 좌표 기반 후기 목록 파라미터는 1.2에서 400으로 거절한다. 내 인근은 좌표를 직접 반경 검색하지 않고 `regions/resolve`로 행정구역을 고른 뒤 `scope=REGION`을 사용한다.

집계 item 형식은 다음과 같다.

```ts
type VisitReviewRegionItem = {
  regionCode: string;
  parentRegionCode: string | null;
  name: string;
  level: 'SIDO' | 'SIGUNGU';
  center: { lat: number; lng: number };
  bounds: { west: number; south: number; east: number; north: number };
  reviewCount: number;
  coverageStatus: 'SUPPORTED' | 'PARTIAL' | 'UNSUPPORTED';
  hasChildren: boolean;
};
```

FE 동작은 다음 정책을 따른다.

- 최초 전국 지도는 시·도별 count marker만 받는다. 게시글 본문과 개별 핀은 받지 않는다.
- 시·도 선택 시 시·군·구 집계를 받는다.
- 지도 이동과 zoom은 표시만 바꾼다. 본문 목록을 자동으로 새로 받지 않는다.
- 시·군·구에서 `이 지역 후기 보기`를 누를 때 첫 20개 후기와 그 페이지의 핀을 받는다.
- 새 지역 조회 성공 시에만 기존 리스트와 핀을 원자적으로 교체한다. 실패하면 이전 결과를 유지한다.
- FE는 requestSequence, AbortController, 늦은 응답 폐기를 사용한다.

## 새 기능: 방문 후기와 좋아요

지도 게시글은 기존 온기 후기가 아니다. 한옥, 한옥 숙박, 한옥 카페, 한옥 체험, 전통시장을 방문하고 남기는 짧은 후기다. 댓글과 대댓글은 없다. 사진도 이번 범위에서 제외한다.

| Method/path | 인증 | 요청 | 성공 |
|---|---|---|---|
| `POST /api/v1/places/{placeId}/visit-reviews` | 회원+CSRF | `{text}` | `201 VisitReview` |
| `DELETE /api/v1/visit-reviews/{id}` | 작성자 | body 없음 | `204` |
| `POST /api/v1/visit-reviews/{id}/reports` | 회원+CSRF | `{reason,detail?}` | `202 {reportId,status}`; 본인 후기 신고는 403 |
| `GET /api/v1/places/{placeId}/visit-reviews` | 공개 | limit,cursor | 장소별 후기 목록 |
| `PUT /api/v1/visit-reviews/{id}/likes/me` | 회원+CSRF | body 없음 | `{likedByMe:true,likeCount}` |
| `DELETE /api/v1/visit-reviews/{id}/likes/me` | 회원+CSRF | body 없음 | `{likedByMe:false,likeCount}` |

후기 text는 NFC/trim 후 1..300 code points, 개행 최대 4개다. HTML 실행, URL 미리보기, 별점, mood, visitorCount는 없다. `mine`과 `likedByMe`는 서버 principal로 계산한다.

좋아요는 toggle API가 아니라 원하는 상태를 보내는 PUT/DELETE다. FE는 후기별 좋아요 요청을 직렬화하고 실패하면 optimistic UI를 이전 값으로 되돌린다. 본인 후기 좋아요는 403이다.

신고는 `SPAM`, `ABUSE`, `PERSONAL_DATA`, `COPYRIGHT`, `OTHER` 중 하나를 보낸다. 같은 회원의 열린 신고는 중복 생성하지 않으며 기존 receipt를 보여 준다. FE는 신고 수나 moderation 판정 사유를 공개하지 않고, 숨김·삭제된 후기는 다음 조회에서 404 또는 목록 제외로 처리한다.

## 기존 여정 탐색 계약에서 FE가 보강해야 할 부분

여정 탐색은 REST command + SSE 진행 알림 + snapshot 복구다. FE는 `POST /explorations` 또는 `POST /explorations/{id}/turns` 뒤 `eventsUrl`을 구독하고, `run.terminal`, reconnect, `reset`, 탭 복귀에는 GET run/snapshot으로 상태를 재동기화한다. EventSource 미지원 환경만 polling fallback을 쓴다.

새로 명확해진 상태는 다음과 같다.

- `RunSnapshot.status`: `QUEUED | RUNNING | COMPLETED | FAILED | CANCELLED`
- `RunSnapshot.outcome`: `INITIAL_BOARD | PROPOSAL | CLARIFICATION_REQUIRED | NO_RESULTS | null`
- `Clarification`: 지역 누락, 지역 모호함, 지원하지 않는 조건일 때 질문을 내려준다.
- `excludedRefs`: 사용자가 제외한 장소 목록이다.
- `pendingProposal`: 기존 board가 있을 때 조건 수정 결과로 내려오는 미적용 제안이다.
- `execution`: `dataMode`, `engine`, `rankingVersion`, `datasetRevision`을 표시한다.

FE는 active run 중 action/save를 보내면 `409 ACTIVE_RUN`을 받을 수 있다. `APPLY_PROPOSAL`은 오래된 `baseVersion`이면 `409 VERSION_CONFLICT`다. browser 연결 종료는 cancel이 아니며, 명시적인 cancel 버튼만 `POST /runs/{runId}/cancel`을 호출한다.

## 인증과 사용자 ID 관련 FE 주의점

MVP 버튼은 Kakao login만 노출해도 된다. 다만 FE 상태와 저장소에서 Kakao ID, 이메일, provider subject를 OnMaru 사용자 ID처럼 쓰지 않는다. 서버의 `GET /api/v1/members/me`가 반환하는 opaque `id`도 public 비교용으로 쓰지 않고, 내 것 여부는 각 DTO의 `mine`, `likedByMe`, `savedByMe`를 사용한다.

OAuth token, Kakao access token, refresh token, client secret은 FE localStorage에 저장하지 않는다. cookie 인증 POST/DELETE에는 `GET /auth/csrf`로 받은 `X-CSRF-TOKEN`을 붙인다.

## 공통 페이징 규칙

새 목록 API는 offset/page 번호를 제공하지 않는다.

```ts
type CursorPage<T> = {
  items: T[];
  nextCursor: string | null;
  hasMore: boolean;
};
```

첫 요청은 cursor를 생략한다. 마지막 페이지는 `hasMore:false,nextCursor:null`이다. 전체 건수가 limit의 배수여도 FE가 추가 빈 페이지를 요청해서 끝을 판단하지 않는다. JSON 배열 인덱스는 0부터이며 화면 순번 1부터는 FE 표시 전용이다.

커서 만료는 `410 CURSOR_EXPIRED`, 필터/limit 변경 또는 변조는 `400 CURSOR_INVALID`, 지역 코드 revision 변경은 `409 REGION_REVISION_CHANGED`다. 이 경우 첫 페이지부터 다시 읽는다.

## FE fixture와 전환 체크리스트

- 장소 카드에서 로그인 전 `savedByMe:false`, 로그인 후 담기 성공, 해제 성공, 중복 PUT 멱등 성공.
- 오디 story 카드에서 담기 성공, 권리/비공개 story 404.
- `GET /saved-resources`는 `type=PLACE`와 `type=ODII_STORY`를 따로 테스트한다.
- `GET /me/timeline`은 `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`가 같은 월 day group으로 묶이는지 테스트한다.
- 타임라인에서 비공개 resource는 item에서 빠지고 `unavailableCount`만 증가하는지 테스트한다.
- 타임라인 item 클릭이 resource type별 상세/재개 화면으로 이동하는지 테스트한다.
- 전국 지도 첫 화면은 region count만 요청하고 후기 본문 요청을 하지 않는다.
- 지도 drag/zoom만으로 기존 목록과 cursor가 바뀌지 않는다.
- `이 지역 후기 보기` 성공 시 목록과 핀이 동시에 교체된다.
- 늦게 도착한 이전 지역 응답은 버린다.
- limit이 정확히 배수인 목록도 `hasMore:false`를 신뢰해 종료한다.
- 방문 후기 작성 후 현재 지역 첫 페이지를 다시 읽고 기존 cursor chain을 버린다.
- 좋아요 PUT/DELETE 실패 시 optimistic UI를 복구한다.

## 아직 FE 구현 계약으로 동결되지 않은 항목

- 지도 1.2 OpenAPI와 JSON fixture의 FE/BE 실행 contract test 연결.
- 지역 경계 정적 자산의 출처, 라이선스, revision 배포 방식.
- 장소/오디 담아두기와 내 월간 타임라인의 OpenAPI/JSON fixture 작성.
- saved resource가 여정 생성 ranking에 영향을 주는 개인화 정책.
- 후기 담아두기와 수동 컬렉션. 이 둘은 [project roadmap](../../project-roadmap.md)의 장기 후보이며 이번 FE 전환 범위가 아니다.
