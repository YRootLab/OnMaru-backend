# Data Contract Spec: 온기 피드 및 혼잡도 히트맵 (`DATA-MAP-WARMTH-HEAT`)

> **Contract ID**: `DATA-MAP-WARMTH-HEAT`  
> **Canonical Source**: `src/map/types.ts`  
> **Used By**: `/map` Page, `<WarmthLayer>`, `<WarmthFeed>`, `<HeatCanvas>`

---

## 1. Schema: 1줄 온기 (`Warmth`) & 리뷰 (`WarmthReview`)

| Field Name | Type | Required | Nullable | Description |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `string` | Yes | No | 온기 고유 식별자 (UUID) |
| `placeId` | `string` | Yes | No | 대상 장소 식별자 (`Place.id`) |
| `placeName` | `string` | Yes | No | 대상 장소 명칭 |
| `lat` | `number` | Yes | No | 온기가 등록된 장소 위도 |
| `lng` | `number` | Yes | No | 온기가 등록된 장소 경도 |
| `text` | `string` | Yes | No | 1줄 온기 후기 본문 (최대 100자) |
| `mood` | `'북적' \| '한적'` | Yes | No | 사용자가 체감한 장소의 정취 분위기 |
| `score` | `1 \| 2 \| 3 \| 4 \| 5` | No | Yes | 5점 척도 정취 점수 |
| `tags` | `string[]` | No | Yes | 감성 키워드 태그 (예: '고즈넉함', '야경맛집') |
| `createdAt` | `string` (ISO 8601) | Yes | No | 작성 일시 |
| `mine` | `boolean` | No | Yes | 본인 작성 여부 (로컬 스토리지/세션) |
| `visitorCount` | `number` | No | Yes | 한국관광 데이터랩 외지인 방문객 수 연동 |

---

## 2. Schema: 관광 혼잡도 히트스팟 (`HeatSpot`)

| Field Name | Type | Required | Nullable | Description |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `string` | Yes | No | 히트스팟 식별자 |
| `placeId` | `string` | Yes | No | 대상 장소 식별자 |
| `name` | `string` | Yes | No | 장소명 |
| `lat` | `number` | Yes | No | 위도 |
| `lng` | `number` | Yes | No | 경도 |
| `district` | `string` | Yes | No | 행정구역 구/군 명칭 (예: '종로구', '경주시') |
| `visitorCount`| `number` | Yes | No | 외지인 일일 방문객 수 |
| `congestionScore` | `number` (0~100) | Yes | No | 종합 혼잡도 지수 |
| `congestionLevel` | `'relaxed' \| 'moderate' \| 'busy' \| 'surge'` | Yes | No | 4단계 혼잡도 등급 |
| `surgeMultiplier` | `number` (1.0~3.5) | Yes | No | 평시 대비 수요 급증 배율 |
| `series` | `number[]` | No | Yes | 7일간 요일별 시계열 혼잡도 배열 (DateScrubber 연동) |

---

## 3. Implementation Evidence

- **Types**: `src/map/types.ts`
- **Repository**: `src/map/warmth/warmthRepo.ts`
- **Heat Engine**: `src/map/warmth/heatScale.ts`, `src/map/warmth/congestion.ts`
