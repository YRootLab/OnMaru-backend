# 홈 화면 API 연동 명세

## 공통

- Base URL: `https://onmaru-backend.onrender.com`
- 응답은 JSON이며 `Cache-Control: no-store`를 사용한다.
- 목록 응답의 `schemaVersion`은 `1.2`다.
- 인증이 없어도 공개 카드와 지역 목록을 조회할 수 있다.
- 저장 여부가 포함되는 응답은 로그인 세션이 있을 때만 `saved`가 `true`가 될 수 있다.

## 이번 주 추천 코스

### `GET /api/v1/home/curated-courses`

기존 한옥 카드 API와 동일한 목록 계약이다. 홈에서는 `limit=20` 또는 `limit=25`를 사용한다.

쿼리 파라미터:

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `limit` | number | 1~50, 기본값 20 |
| `cursor` | string | 다음 페이지 cursor |
| `category` | string | `HANOK`, `HANOK_CAFE`, `HANOK_EXPERIENCE`, `HISTORIC_SITE`, `NATURE_SITE`, `LEISURE_ACTIVITY`, `TRADITIONAL_MARKET` |
| `regionCode` | string | 행정 지역 코드 |
| `keyword` | string | 장소명·요약 검색 |
| `hasImage` | boolean | 이미지가 있는 카드만 조회 |

응답 예시:

```json
{
  "schemaVersion": "1.2",
  "items": [
    {
      "placeId": "p-gyeongbokgung",
      "name": "경복궁",
      "category": "HISTORIC_SITE",
      "regionName": "서울 종로구",
      "thumbnailUrl": null,
      "summary": "조선 왕조의 정궁과 궁궐 건축을 함께 만나는 대표 역사 문화유산입니다.",
      "tags": ["문화재", "궁궐", "역사", "산책"],
      "saved": false
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

현재 Spring 기본 projection에는 전국 대표 장소 25개가 포함되어 있다. 운영 ingestion이 활성화되면 동일한 응답 계약으로 원천 데이터가 교체될 수 있으므로 FE는 카드 수를 고정하지 말고 `items`와 `hasMore`를 기준으로 렌더링한다.

## 인기 한옥 소리

### `GET /api/v1/home/trending-sounds`

Odii 활성 스토리를 조회한다. 데이터셋이 일시적으로 unavailable이면 `503 SERVICE_UNAVAILABLE`을 반환하며 FE는 기존 안내 UI 또는 재시도 UI를 표시한다. 임의의 가짜 오디오 카드로 성공 응답을 만들지 않는다.

쿼리 파라미터:

- `language`: 기본 `ko-KR`
- `category`
- `regionCode`
- `limit`: 기본 20
- `cursor`

기존 Odii stories 응답의 `items`, `nextCursor`, `hasMore`를 그대로 사용한다.

## 인기 지역

### `GET /api/v1/home/popular-regions`

공개 방문 후기 지역 집계를 사용한다.

- 선택 파라미터: `parentRegionCode`
- 응답은 `/api/v1/visit-review-regions`와 동일한 지역 집계 계약이다.
- 지역명을 FE에서 별도 하드코딩하지 않는다.

## 검색창

### `POST /api/journey-curator/explore`

기존 canonical endpoint `POST /api/v1/explorations`의 호환 경로다. 요청·응답·CSRF·`Idempotency-Key` 정책은 canonical endpoint와 동일하다.

```json
{
  "query": "경주에서 한옥과 자연을 함께 보고 싶어요",
  "locale": "ko-KR",
  "regionCode": "kr-47-gyeongju"
}
```

FE는 성공 시 `202` 응답의 `explorationId`, `runId`, `snapshotUrl`을 저장하고, 이후 기존 exploration polling/SSE 흐름을 사용한다.

## 오류 처리

- `400 INVALID_REQUEST`: 파라미터 형식 또는 지원하지 않는 category
- `503 SERVICE_UNAVAILABLE`: 원천 데이터셋 또는 집계 서비스 일시 장애
- `cursor` 오류: 기존 목록 API의 `CURSOR_INVALID`, `CURSOR_EXPIRED` 계약 사용

