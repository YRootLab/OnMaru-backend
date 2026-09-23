# 소리마루(Sorimaru) API FE 전달 보고서

**관련 이슈:** [#345](https://github.com/YRootLab/OnMaru-backend/issues/345)

**상태:** 구현 완료, PR 검토 대기

## 이번 변경의 목적

FE가 Odii 제공기관 API를 직접 호출하거나 API Key·로컬 캐시를 관리하지 않고, OnMaru backend를 Single Source of Truth로 사용하도록 호환 API를 추가했습니다.

이번 변경에서는 Redis와 Whale.Be 외부 추천 API를 추가하지 않았습니다. 현재 응답 데이터는 backend의 active Odii revision에서 조회됩니다.

## FE가 사용할 API

### 1. 키워드 스토리 검색

```http
GET /api/stories?keyword=한옥&language=ko-KR&limit=20
```

- `keyword`: 필수, 1~80자
- `language`: 선택, 기본 `ko-KR`
- `limit`: 선택, 1~50, 기본 `20`
- 검색 대상: story title, audio title, `contentTags`
- 응답은 기존 Odii 목록과 동일한 `items`, `nextCursor`, `hasMore` 구조입니다.

### 2. 근처 스토리

```http
GET /api/stories/nearby?lat=35.817632&lng=127.152948&radius=5000&limit=20
```

- `lat`: 필수, -90~90
- `lng`: 필수, -180~180
- `radius`: 선택, 미터 단위 1~50,000, 기본 `5000`
- `language`, `limit`: 검색 API와 동일
- 결과는 Haversine 거리 오름차순, 동률이면 게시일 최신순입니다.
- 각 `item.coordinates`에는 story의 위도·경도가 포함됩니다.

### 3. 키워드 추천

```http
GET /api/recommendation?keyword=궁궐&language=ko-KR&limit=20
```

- `keyword`: 필수, 1~80자
- 제목 exact match → 제목 부분 match → audio title → contentTags 순으로 점수화합니다.
- 동률은 게시일 최신순과 story ID 순으로 정렬합니다.
- 사용자별 추천 결과를 저장하거나 외부 추천 서비스를 호출하지 않습니다.

## 공통 응답 예시

```json
{
  "schemaVersion": "1.2",
  "coverageStatus": "COMPLETE",
  "language": "ko-KR",
  "languageStatus": "EXACT",
  "items": [
    {
      "storyId": "odii-story-jeonju-hanok-01",
      "title": "전주의 한옥 골목 이야기",
      "audioTitle": "전주 한옥마을 산책",
      "category": "한옥/고택",
      "region": { "regionCode": "kr-45-jeonju" },
      "coordinates": { "lat": 35.817632, "lng": 127.152948 },
      "durationSeconds": 185,
      "imageUrl": "https://cdn.onmaru.example/odii/story.jpg",
      "linkedPlaceId": "p-jeonju-hanok-village",
      "contentTags": ["한옥 골목"],
      "savedByMe": false
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

로그인 세션 쿠키가 없으면 `savedByMe`는 `false`입니다. 기존과 같이 `credentials: "include"`를 유지해 주세요.

## 오류 및 UI 처리

| HTTP | code | FE 처리 |
| --- | --- | --- |
| `400` | `INVALID_REQUEST` | 입력값 오류 안내 또는 기본 검색 조건 복원 |
| `503` | `SERVICE_UNAVAILABLE` | 빈 목록·재시도 UI 표시. 가짜 카드로 대체하지 않음 |

검색 결과가 없을 때는 오류가 아니라 `200` + `items: []`입니다.

## 기존 API와의 관계

기존 API는 삭제하지 않았습니다.

- `GET /api/v1/odii/stories`: 언어·카테고리·지역·cursor 기반 canonical 목록
- `GET /api/v1/odii/stories/{storyId}`: 상세·오디오·대본
- `GET /api/v1/home/trending-sounds`: 홈 오디오 목록
- `GET /api/v1/home/popular-sounds`: 재생·저장 기반 인기 오디오

신규 세 API는 #345의 프론트 정리 코드가 사용할 호환 경로입니다.

## 검증 결과

- Odii query service 단위 테스트 통과
- Spring MockMvc 경계 테스트 통과
- `./gradlew test --no-daemon --max-workers=1` 통과
- `bash scripts/verify-contracts` 통과
- Redis 의존성·설정·캐시 코드 미추가

상세 계약은 [sorimaru-api.md](./sorimaru-api.md)를 함께 참고해 주세요.
