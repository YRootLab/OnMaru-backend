# FE 변경 전달서: 새 기능과 추가 API

2026-09-12 기준. 이 문서는 기존 FE에 없거나 기존 FE 계약과 달라진 기능을 FE 작업자가 한 번에 볼 수 있도록 정리한 전달서다. 실제 구현 완료나 배포 완료를 의미하지 않는다. `docs/specs` 원본은 외부 symlink이므로 이 저장소에서 직접 수정하지 않았다.

최신 설계 기준은 다음 순서로 읽는다.

1. 지도/지역/수집 변경은 [인증 확장·원천 수집·행정구역 지도](regional-map-and-ingestion.md)의 1.2 제안이 우선한다.
2. 여정 탐색, 저장, 공통 오류, 기존 VisitReview 상세는 [FE REST 계약](api-contract.md)을 따른다.
3. 회원·소유권·보존 정책은 [인증·영속 모델·보존](data-and-identity.md)을 따른다.

## FE가 새로 알아야 하는 큰 변화

| 구분 | 기존 FE에 없거나 달라진 점 | FE 영향 |
|---|---|---|
| 개인 담아두기 | 장소와 오디 이야기를 개별로 담아둔다. 여정 저장과 다른 기능이다. | 장소 카드/오디 카드에 `savedByMe` 상태와 담기/해제 액션이 필요하다. |
| 내 월간 타임라인 | 담아둔 장소·오디 이야기·저장 여정을 월별 흐름으로 보여준다. | 내 정보 화면은 단순 컬렉션 목록보다 월간 타임라인을 우선한다. |
| 지도 후기 | 기존 온기와 별도인 짧은 방문 후기 `VisitReview`를 사용한다. | 온기 mood/score UI를 재사용하지 않는다. 좋아요는 있지만 대댓글은 없다. |
| 지도 조회 방식 1.2 | 자동 viewport 본문 조회보다 행정구역 집계→지역 선택→명시적 목록 조회를 우선한다. | 지도 이동만으로 리스트를 갈아끼우지 않는다. 사용자가 지역을 선택하고 후기 보기를 눌러 목록을 읽는다. |
| 커서 페이징 | page/pageIndex/offset/totalPages를 쓰지 않는다. | `items`, `hasMore`, `nextCursor`만 본다. 배열 인덱스는 0부터이며 화면 순번은 FE 전용이다. |
| 인증 확장 | MVP는 Kakao지만 내부 회원 ID는 provider 독립 UUID다. | FE는 provider subject/email을 사용자 ID로 저장하거나 비교하지 않는다. |
| 여정 run 상태 | REST command + polling이 현재 MVP 통신이다. | SSE UI를 전제로 만들지 않는다. run 완료 후 snapshot을 다시 읽는다. |

## 새 API: 장소와 오디 담아두기

담아두기는 사용자가 마음에 드는 개별 resource를 개인 라이브러리에 저장하는 기능이다. `saved_journeys`와 다르다. 여정 저장은 여행길 전체 snapshot이고, 담아두기는 현재 공개 projection을 다시 읽는 참조 저장이다.

| Method/path | 인증 | 요청 | 성공 응답 |
|---|---|---|---|
| `PUT /api/v1/saved-resources/places/{placeId}` | 회원+CSRF | body 없음 | `200 {resourceType:"PLACE",resourceId,savedByMe:true,savedAt}` |
| `DELETE /api/v1/saved-resources/places/{placeId}` | 회원+CSRF | body 없음 | `204`, 이미 없어도 성공 |
| `PUT /api/v1/saved-resources/odii-stories/{storyId}` | 회원+CSRF | body 없음 | `200 {resourceType:"ODII_STORY",resourceId,savedByMe:true,savedAt}` |
| `DELETE /api/v1/saved-resources/odii-stories/{storyId}` | 회원+CSRF | body 없음 | `204`, 이미 없어도 성공 |
| `GET /api/v1/saved-resources?type=PLACE&limit=20&cursor=...` | 회원 | query | 장소 담아두기 목록 |
| `GET /api/v1/saved-resources?type=ODII_STORY&limit=20&cursor=...` | 회원 | query | 오디 이야기 담아두기 목록 |

`type` 생략으로 장소와 오디를 섞어 반환하지 않는다. 목록 정렬은 `savedAt DESC,id DESC`다. `limit`은 1..50, 기본 20이다. 커서는 member/type/limit/asOf/lastSavedAt/lastId에 묶이고 10분 뒤 만료된다.

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

비회원은 서버에 담아두기를 쓰지 않는다. FE가 임시 intent를 기억했다가 로그인 후 같은 PUT을 다시 보낸다. 로그인하지 않은 public 카드 응답은 `savedByMe:false`로 표시할 수 있지만 localStorage 상태를 서버 정답처럼 쓰지 않는다.

## 새 API: 내 월간 타임라인

내 정보 화면은 단순히 장소/오디/여정을 각각 나열하는 컬렉션보다, 사용자가 월별로 어떤 장소와 이야기를 담고 어떤 여정을 저장했는지 보여주는 타임라인을 우선한다. 이 기능은 “자연어 탐색에서 발견한 후보를 담고, 나중에 다시 이어 보는 흐름”을 FE에서 보여주기 위한 개인 화면이다.

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

초기 타임라인 item은 `SAVED_PLACE`, `SAVED_ODII_STORY`, `SAVED_JOURNEY`다. `WROTE_VISIT_REVIEW`는 VisitReview 작성 기능 구현 뒤 추가한다. FE는 아직 지원하지 않는 item type을 받으면 해당 item을 숨기고 telemetry 또는 개발 로그로 남긴다.

FE 화면 흐름은 다음을 권장한다.

1. 내 정보 첫 화면 상단에 현재 월 타임라인을 보여준다.
2. 월 선택기는 `이번 달`, `지난 달`, 직접 월 선택을 제공한다.
3. 같은 날짜의 여러 활동은 하나의 day group으로 묶는다.
4. 장소 item 클릭은 장소 상세로 이동한다.
5. 오디 item 클릭은 오디 story 또는 연결된 장소 상세로 이동한다.
6. 저장 여정 item 클릭은 saved journey 상세 또는 resume 동작으로 이어진다.
7. 비공개/삭제된 resource는 item으로 노출하지 않고 월 단위 `unavailableCount`만 표시할 수 있다.
8. 타임라인에서 항목을 지우는 별도 API는 만들지 않는다. 장소/오디는 담아두기 해제, 여정은 saved journey 삭제를 호출한다.

## 새 API: 행정구역 지도 집계 1.2

기존 `VIEWPORT` 자동 조회안은 1.1 초안이다. 새 지도 구현은 1.2 제안으로 전환해야 한다. 1.2 OpenAPI와 fixture는 아직 작성되지 않았다.

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

지도 게시글은 기존 온기 후기가 아니다. 한옥, 한옥 숙박, 한옥 카페, 전통시장을 방문하고 남기는 짧은 후기다. 댓글과 대댓글은 없다. 사진도 이번 범위에서 제외한다.

| Method/path | 인증 | 요청 | 성공 |
|---|---|---|---|
| `POST /api/v1/places/{placeId}/visit-reviews` | 회원+CSRF | `{text}` | `201 VisitReview` |
| `DELETE /api/v1/visit-reviews/{id}` | 작성자 | body 없음 | `204` |
| `GET /api/v1/places/{placeId}/visit-reviews` | 공개 | limit,cursor | 장소별 후기 목록 |
| `PUT /api/v1/visit-reviews/{id}/likes/me` | 회원+CSRF | body 없음 | `{likedByMe:true,likeCount}` |
| `DELETE /api/v1/visit-reviews/{id}/likes/me` | 회원+CSRF | body 없음 | `{likedByMe:false,likeCount}` |

후기 text는 NFC/trim 후 1..300 code points, 개행 최대 4개다. HTML 실행, URL 미리보기, 별점, mood, visitorCount는 없다. `mine`과 `likedByMe`는 서버 principal로 계산한다.

좋아요는 toggle API가 아니라 원하는 상태를 보내는 PUT/DELETE다. FE는 후기별 좋아요 요청을 직렬화하고 실패하면 optimistic UI를 이전 값으로 되돌린다. 본인 후기 좋아요는 403이다.

## 기존 여정 탐색 계약에서 FE가 보강해야 할 부분

여정 탐색은 REST command + polling이다. FE는 `POST /explorations` 또는 `POST /explorations/{id}/turns` 후 받은 run URL을 1초 간격으로 조회하고, terminal 상태가 되면 snapshot을 다시 읽는다.

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

- 지도 1.2 OpenAPI와 JSON fixture.
- 지역 경계 정적 자산의 출처, 라이선스, revision 배포 방식.
- 장소/오디 담아두기 OpenAPI.
- 내 월간 타임라인 OpenAPI와 JSON fixture.
- saved resource가 여정 생성 ranking에 영향을 주는 개인화 정책.
- 후기 담아두기와 수동 컬렉션. 이 둘은 [project roadmap](../../../project-roadmap.md)의 장기 후보이며 이번 FE 전환 범위가 아니다.
