# Data Contract Spec: 한옥 도감 엔티티 (`DATA-HANOK-001`)

> **Contract ID**: `DATA-HANOK-001`  
> **Model Name**: `Village`, `VillageMeta`  
> **Canonical Source**: `src/hanok/types.ts`  
> **Source Type**: External API (TourAPI 4.0 `areaBasedList2`) + Normalizer Adapter  
> **Used By**: `/hanok` Page, `<HanokGrid>`, `<HanokMap>`, `<HanokMonthly>`

---

## 1. Schema Definition (`Village`)

| Field Name | Primitive Type | Required | Nullable | Semantic Description | External Source Mapping (`TourAPI`) |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `id` | `string` | Yes | No | TourAPI 고유 콘텐츠 식별자 | `item.contentid` |
| `name` | `string` | Yes | No | 장소/한옥 명칭 (타이틀) | `String(item.title).trim()` |
| `rawTitle` | `string` | No | Yes | 원본 타이틀 | `item.title` |
| `region` | `string` | Yes | No | 17개 표준 시/도 권역 (서울, 경기 등) | `resolveRegion(areacode, addr1)` |
| `addr` | `string` | Yes | No | 도로명 또는 지번 주소 | `String(item.addr1).trim()` |
| `lat` | `number` | No | Yes | WGS84 위도 (33.0 ~ 39.0 범위) | `parseFloat(item.mapy)` (범위 외 null) |
| `lng` | `number` | No | Yes | WGS84 경도 (124.0 ~ 132.0 범위) | `parseFloat(item.mapx)` (범위 외 null) |
| `type` | `string` | Yes | No | 한옥 분류 유형 | 카테고리 매핑 룰에 의해 부여 |
| `badges` | `string[]` | Yes | No | 텍스트 룰 기반 자동 생성 뱃지 (최대 3개) | `assignBadges(title, addr)` |
| `image` | `string` | No | Yes | 대표 이미지 HTTPS URL | `toHttps(item.firstimage \|\| item.firstimage2)` |
| `hasImage` | `boolean` | Yes | No | 이미지 유효 보유 여부 | `Boolean(item.firstimage \|\| item.firstimage2)` |
| `summary` | `string` | Yes | No | 한 줄 요약 텍스트 | `${title} - ${addr}` |
| `overview` | `string` | Yes | No | 상세 개요 텍스트 | 상세 API 연동 전 기본 빈 문자열 |

---

## 2. Metadata Schema (`VillageMeta`)

| Field Name | Type | Description |
| :--- | :--- | :--- |
| `generatedAt` | `string` (ISO 8601) | 데이터 수집 및 정규화 시각 |
| `total` | `number` | 수집된 총 한옥 레코드 수 |
| `byType` | `Record<string, number>` | 유형별 한옥 분포 통계 |
| `imageRate` | `number` (0.0 ~ 1.0) | 이미지 보유 비율 |
| `badgeStats` | `Record<string, number>` | 뱃지별 빈도 통계 |
| `badgeFallbackCount`| `number` | 뱃지 폴백 횟수 |

---

## 3. Transformation & Validation Rules

1. **위경도 유효성 검증 (`inKorea`)**:
   - `lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132` 범위를 벗어나거나 NaN인 경우 `lat = null`, `lng = null`로 정규화.
2. **HTTPS 프로토콜 강제 (`toHttps`)**:
   - `http://`로 제공되는 공공데이터 이미지 URL을 `https://`로 치환하여 Mixed Content 보안 경고 방지.
3. **뱃지 추출 규칙 (`BADGE_RULES`)**:
   - 명칭 및 주소 내 키워드 매칭(예: '유네스코', '국보', '종택', '서원' 등)을 통해 상위 3개 뱃지 자동 추출.

---

## 4. Backend Persistence Implication

- **Entity Candidate**: `Place` 테이블 및 `HanokDetail` 확장 테이블
- **Unique Constraint**: `contentId` (TourAPI 식별자)
- **Indexing**: `(region, type)`, `(lat, lng)` 공간 인덱스(PostGIS) 권장

---

## 5. Implementation Evidence

- **Types**: `src/hanok/types.ts`
- **Adapter**: `src/hanok/services/hanokArchive.service.ts`
- **Decoder/Fallback**: `src/hanok/data/hanokArchiveFallback.ts`
