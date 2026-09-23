# 이번 주 인기 한옥 소리 TOP N API (#317)

홈 "인기 한옥 소리" 섹션용 랭킹 API 계약이다. 인기 점수 = `2 × 최근 7일 재생 수 + 1 × 최근 7일 저장 수`.

## 1. `GET /api/v1/home/popular-sounds`

호환 경로: `GET /api/home/popular-sounds`

쿼리 파라미터:

| 이름 | 타입 | 기본 | 설명 |
| --- | --- | --- | --- |
| `limit` | number | 7 | 1~20 (TOP N) |
| `window` | string | `week` | `week`(최근 7일) 또는 `all`(전체) |
| `language` | string | `ko-KR` | ko-KR, en-US 등. 스토리가 없으면 ko-KR 폴백 |
| `category` | string | 없음 | 선택 카테고리 필터 |

응답 예시:

```json
{
  "schemaVersion": "1.2",
  "basis": "POPULARITY",
  "window": "week",
  "language": "ko-KR",
  "languageStatus": "EXACT",
  "items": [
    {
      "rank": 1,
      "score": 4,
      "playCount": 2,
      "saveCount": 0,
      "story": {
        "storyId": "odii-story-namsangol-01",
        "title": "남산골 한옥마을",
        "category": "한옥/고택",
        "region": { "regionCode": "kr-11-jongno", "name": "서울 종로구", "level": "CITY", "parentRegionCode": "kr-11" },
        "durationSeconds": 185,
        "imageUrl": "https://cdn.onmaru.example/odii/odii-story-namsangol-01.jpg",
        "savedByMe": false
      }
    }
  ]
}
```

- `basis`:
  - `POPULARITY` — 재생·저장 신호가 1건이라도 있어 점수 순 정렬
  - `FALLBACK_RECENT` — 신호가 전부 0이면 최근 게시 순 (`score`는 0)
- FE는 신호 유무와 무관하게 항상 `items` 기준으로 렌더링한다 (카드 수 고정 금지).
- `503 SERVICE_UNAVAILABLE`: Odii 데이터셋 미적재 → 재시도 UI.
- `400 INVALID_REQUEST`: window/language/limit 형식 오류.

## 2. `POST /api/v1/odii/stories/{storyId}/plays`

재생 시작 시 1회 호출해 재생 수를 기록한다 (랭킹 신호).

- 헤더: 기존 POST 계약과 동일하게 CSRF 필요 (`X-CSRF-TOKEN` + `__Host-onmaru-csrf` 쿠키, `credentials: "include"`).
- 인증 없이 호출 가능하나 세션이 있으면 자연스럽다.
- 응답: `200 { "schemaVersion": "1.2", "storyId": "...", "recorded": true }`
- `404`: 스토리 없음. 재생은 실패해도 UI에 영향을 주지 않으므로 FE는 조용히 무시해도 된다.

## 3. 참고

- Odii 수집 자체가 `keyword=한옥`이므로 데이터셋 전체가 한옥 관련이다. 세부 필터가 필요하면 `category` 사용.
- 저장 수의 원천은 사용자 찜(#318 영속화)이다. 재배포해도 찜·재생 집계는 유지된다.
- 기존 `GET /api/v1/home/trending-sounds`(최근 게시순 목록) 계약은 변경되지 않았다.
