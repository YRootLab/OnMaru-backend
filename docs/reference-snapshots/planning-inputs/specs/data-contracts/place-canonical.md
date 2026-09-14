# Data Contract Spec: 통합 장소 정규 모델 (`DATA-PLACE-CANONICAL`)

> **Contract ID**: `DATA-PLACE-CANONICAL`  
> **Model Name**: `CanonicalPlace`  
> **Status**: Proposal for Backend Domain Modeling (도메인 파편화 통합 표준)  
> **Target Domains**: Hanok Archive, Map Info/Warmth, Odii Audio Story

---

## 1. Background & Consolidation Rationale

현재 OnMaru 프론트엔드에서는 장소와 위치 개념이 각 도메인마다 서로 다른 필드명과 타입으로 정의되어 있습니다:
- **Hanok**: `Village` (`lat: number | null`, `lng: number | null`, `id: string`)
- **Map**: `Item` (`lat: number`, `lng: number`, `id: string`, `dist: number | null`)
- **Odii**: `OdiiStoryItem` (`mapX: string`, `mapY: string`, `stid: string`)

이를 백엔드 공통 엔티티 및 표준 DTO로 통합하기 위한 정규 데이터 계약을 정의합니다.

---

## 2. Canonical Schema Definition

| Field Name | Type | Required | Nullable | Description | Source Mapping |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `id` | `string` | Yes | No | 장소 통합 UUID 또는 TourAPI contentId | `contentId \|\| id \|\| stid` |
| `sourceContentId`| `string` | No | Yes | TourAPI 공공데이터 원본 ID | `item.contentid` |
| `name` | `string` | Yes | No | 표준 장소 명칭 | `title \|\| name` |
| `category` | `string` | Yes | No | 표준 분류 코드 (`HERITAGE`, `STAY`, `CULTURE`, `FOOD`, `CAFE` 등) | Category Normalizer |
| `latitude` | `number` | No | Yes | WGS84 위도 (33.0 ~ 39.0 범위) | `lat \|\| parseFloat(mapY)` |
| `longitude` | `number` | No | Yes | WGS84 경도 (124.0 ~ 132.0 범위) | `lng \|\| parseFloat(mapX)` |
| `address` | `string` | Yes | No | 도로명 또는 지번 주소 | `addr \|\| addr1` |
| `region` | `string` | Yes | No | 17개 표준 시/도 권역 명칭 | Region Normalizer |
| `telephone` | `string` | No | Yes | 대표 연락처 | `tel` |
| `imageUrl` | `string` | No | Yes | 대표 이미지 HTTPS URL | `image \|\| firstimage` |
| `hasImage` | `boolean` | Yes | No | 대표 이미지 보유 여부 플래그 | `Boolean(imageUrl)` |
| `isTraditional`| `boolean` | Yes | No | 전통 한옥/문화재/고택 여부 플래그 | `isTraditional \|\| true` |
| `overview` | `string` | No | Yes | 장소 상세 설명 텍스트 | `overview` |

---

## 3. Database & API Design Implication

- **Table**: `places`
- **Spatial Index**: `ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)` PostGIS 지리 공간 인덱스 생성
- **Unique Key**: `source_content_id` (TourAPI 중복 적재 방지)
