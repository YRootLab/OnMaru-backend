# FE API 호환 및 한옥 목록 정책 전달

**관련 이슈:** #342, #343, #344
**상태:** 구현 완료, 배포 후 사용 가능

## 이번 변경 요약

FE가 기존에 호출하던 지도·장소 경로를 backend가 호환합니다. 신규 경로가 기존 versioned API와 같은 서비스·검증·응답을 사용하므로, FE가 별도 변환하거나 두 응답을 다르게 처리할 필요는 없습니다.

한옥 도감의 기본 목록에서는 `HANOK_CAFE`를 제외합니다. 이 정책은 한옥 도감에만 적용되며, 홈 큐레이션은 기존 카테고리 구성을 유지합니다.

## FE에서 사용할 API

### 지도 장소 목록

```http
GET /api/map/places?regionCode=kr-45-jeonju&limit=20
```

- 기존 canonical 경로 `GET /api/v1/map/places`와 동일합니다.
- `language`, `regionCode`, `bbox`, `lat`, `lng`, `radius`, `category`, `limit`를 그대로 사용합니다.
- 지도 이동은 `bbox=minLng,minLat,maxLng,maxLat` 또는 `lat`·`lng`·`radius` 중 하나로 범위를 전달합니다.
- 응답은 `schemaVersion`, `coverageStatus`, `items`, `nextCursor`, `hasMore`를 포함합니다.
- `items[].savedByMe`를 사용하려면 기존처럼 `credentials: "include"`가 필요합니다.

### 장소 상세

```http
GET /api/place/{placeId}
```

- canonical 경로 `GET /api/v1/places/{placeId}`와 동일합니다.
- `placeId`, `name`, `category`, `region`, `address`, `coordinates`, `images`, `description`, `contentTags`, `savedByMe`를 반환합니다.
- `address`와 `coordinates`는 `null`, `images`는 빈 배열일 수 있으므로 정상적인 빈 상태 UI를 사용해 주세요.

### 지도 혼잡도 히트맵

```http
GET /api/map/heat?date=2026-09-14&regionCode=kr-45-jeonju&metric=CONGESTION_SCORE
```

- canonical 경로 `GET /api/v1/insights/heatmap`와 동일합니다.
- `date`는 필수이며 `YYYY-MM-DD` 형식입니다. 날짜 스크러버의 선택값을 그대로 전달합니다.
- 응답은 `coverageStatus`, `metric`, `observedDate`, `generatedAt`, `spots`를 포함합니다.
- 데이터가 없을 때도 임의의 0값을 만들지 않고 `200`과 `coverageStatus: "MISSING"`, 빈 `spots`를 반환합니다.

### 한옥 도감 목록 정책 변경

```http
GET /api/v1/hanoks?limit=50
```

- 기본 목록과 `category=HANOK_CAFE` 요청 모두 한옥 카페를 반환하지 않습니다.
- 따라서 FE는 한옥 도감에서 `HANOK_CAFE` 카드용 분기나 클라이언트 측 제외 필터를 유지할 필요가 없습니다.
- 홈의 `GET /api/v1/home/curated-courses`는 이 정책의 대상이 아니며 기존처럼 한옥 카페를 포함할 수 있습니다.

### 온기 후기는 VisitReview API 사용

별도 `/api/map/warmth`는 만들지 않습니다. 지도 온기 후기 UI는 아래 VisitReview API를 사용해 주세요.

#### FE 명칭 ↔ API 모델 매핑

지도 화면에서 FE가 부르는 `visited` 또는 “지도 게시글”은 별도 리소스가 아니라 **VisitReview 한 건**입니다. 화면 표기만 달라도 아래 API와 동일한 `id`를 사용합니다.

| FE 화면/상태 명칭 | API 모델·필드 | 비고 |
|---|---|---|
| `visited`, 지도 게시글, 온기 후기 | `VisitReview` | 같은 후기 데이터. 별도 `/visited`, `/warmths`, `/api/map/warmth` API 없음 |
| `visited.id` | `VisitReview.id` | 삭제·좋아요·신고 경로의 `{reviewId}` |
| `visited.placeId` | `VisitReview.placeId` | 장소 상세와 연결하는 canonical ID |
| `visited.isMine` | `VisitReview.mine` | FE에서 `isMine`으로 이름만 변환 가능 |
| `visited.likes` | `VisitReview.likeCount` | 좋아요 여부는 별도 `likedByMe` |
| `visited.mood/score/tags` | 동명 필드 | `mood`, `score`는 null일 수 있고 `tags`는 빈 배열일 수 있음 |

```http
GET  /api/v1/places/{placeId}/visit-reviews?limit=20
POST /api/v1/places/{placeId}/visit-reviews
GET  /api/v1/visit-reviews?scope=REGION&regionCode=kr-45-jeonju&limit=20
PUT  /api/v1/visit-reviews/{reviewId}/likes/me
DELETE /api/v1/visit-reviews/{reviewId}/likes/me
DELETE /api/v1/visit-reviews/{reviewId}
POST /api/v1/visit-reviews/{reviewId}/reports
```

- 목록 item은 `id`, `placeId`, `placeName`, `lat`, `lng`, `text`, `mood`, `score`, `tags`, `visitorCount`, `createdAt`, `mine`, `likeCount`, `likedByMe`를 반환합니다. 장소별 후기 핀과 피드는 동일 응답으로 그립니다.
- 작성 요청은 다음과 같습니다. `mood`, `score`, `tags`는 선택값이라 기존 `{ "text": "..." }` 요청도 호환됩니다.

```json
{
  "text": "비 오는 날 처마 아래가 특히 좋았어요.",
  "mood": "한적",
  "score": 5,
  "tags": ["고즈넉함", "처마"]
}
```

- `mood`는 `북적` 또는 `한적`, `score`는 1~5, `tags`는 최대 5개이며 각 태그는 최대 20 code points입니다. 잘못된 값은 `400 VALIDATION_ERROR`와 `details.field`로 돌려줍니다.
- 로그인 세션과 CSRF 토큰이 필요하며, 재시도 시 `Idempotency-Key`를 함께 보냅니다.
- 좋아요는 toggle이 아니라 원하는 상태를 명시합니다. 등록은 `PUT`, 취소는 `DELETE`이며 실패한 optimistic UI는 되돌립니다.
- 지역 피드는 `scope=ALL`, `scope=REGION&regionCode=...`, 로그인 회원의 `scope=MY`를 사용합니다. 지도 뷰포트·반경으로 후기 본문을 요청하지 않습니다.

`visitorCount`는 후기 장소가 속한 지역의 활성 한국관광 DataLab 일 단위 관측값입니다. 장소별 실시간 인원도 아니고 후기 작성 시점의 값도 아닙니다. 원천에 값이 없거나 아직 수집되지 않은 지역은 `null`로 반환되며, FE는 이를 0명으로 표시하거나 로컬 값으로 보완하지 말아 주세요. 원천의 소수 관측값은 API 정수 계약에 맞춰 가장 가까운 1명으로 반올림합니다. 화면에는 `mood`·`score`·`tags`가 `null` 또는 빈 배열인 기존 후기 상태도 표시할 수 있어야 합니다.

## FE 공통 처리

- public 조회 API는 `Cache-Control: no-store`를 반환합니다. FE가 별도 캐시 일관성 로직을 둘 필요는 없습니다.
- 입력 오류는 `400` + `INVALID_REQUEST` 또는 `VALIDATION_ERROR`입니다. 사용자 입력을 초기화하거나 재선택할 수 있게 처리해 주세요.
- 카탈로그 일시 장애는 `503` + `SERVICE_UNAVAILABLE`입니다. 빈 데이터로 성공 처리하지 말고 재시도 UI를 표시해 주세요.

## 이번 범위에 포함하지 않은 항목

- `/api/map/warmth`는 제공하지 않습니다. 온기 후기 UI는 위 VisitReview API로 통합합니다.
- `/visited`, `/warmths` 같은 별도 지도 게시글 API도 제공하지 않습니다. 위 명칭 매핑으로 VisitReview를 사용합니다.
- `/api/v1/hanoks/screen-hanok`는 이미 구현돼 있습니다. 현재 운영 데이터가 비어 있는 것은 API 경로 문제가 아니라 스크린 한옥 수집·게시 데이터 문제입니다.
- `/api/stories`, `/api/stories/nearby`, `/api/recommendation`은 이미 `develop`에 구현되어 이번 변경에 중복 포함하지 않았습니다.

## 검증

- 지도 장소·장소 상세·히트맵 호환 경로 MockMvc 경계 테스트 통과
- 한옥 도감 카페 제외 및 홈 큐레이션 회귀 테스트 통과
- R1 fixture/E2E 테스트와 `bash scripts/verify-contracts` 통과
