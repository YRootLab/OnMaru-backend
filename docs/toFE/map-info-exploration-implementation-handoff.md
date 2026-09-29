# FE 전달 보고서: 지도 정보모드 탐색·목록·클러스터 구현

## 1. 목적

상위 설계는 [공동 설계 스펙](../superpowers/specs/2026-09-29-map-info-exploration-design.md)이다.

현재 FE는 category가 선택되면 TourAPI를 여러 번 직접 호출하는 fallback 경로가 있다. 이번 변경에서는 정보모드 장소 조회를 BE API로 통일한다.

## 2. 확정 UX

### 기본 진입

- 기본 category: `spot`
- 표시명: `고택`
- URL: `/map?mode=info&category=spot`
- 왼쪽 제목: `고택 목록`
- 지도: 고택·역사유산·한옥마을 등 BE가 확장한 장소 표시

`전체`를 기본으로 선택하지 않는다. 전체 데이터 탐색은 사용자가 `전체`를 선택했을 때 시작한다.

### category 목록

```text
spot        고택
experience  전통 체험
culture     문화유산
festival    축제
stay        한옥 숙소
food        전통 맛집
cafe        한옥 카페
market      전통 시장
all         전체
```

FE 표시 category와 BE canonical category는 1:1이 아니다. FE는 BE 응답의 `displayCategory`, `matchedCategories`, `appliedCategories`를 사용하고 자체 키워드 검색으로 재분류하지 않는다.

## 3. 사용할 BE API

### 3.1 왼쪽 목록

```http
GET /api/v1/map/info/places
```

요청 예:

```text
/api/v1/map/info/places?category=SPOT&limit=30
/api/v1/map/info/places?category=ALL&regionCode=11&cursor={cursor}&limit=30
```

FE 사용 필드:

```json
{
  "query": { "category": "SPOT" },
  "snapshot": { "id": "rev-...", "publishedAt": "..." },
  "totalCount": 1284,
  "items": [],
  "nextCursor": "...",
  "appliedCategories": ["HANOK", "HISTORIC_SITE"],
  "coverage": "COMPLETE"
}
```

필수 동작:

- `totalCount`는 전체 수이며 `items.length`와 다르다.
- `nextCursor`가 있을 때 목록 sentinel에서 다음 page를 요청한다.
- category/region 변경 시 cursor와 기존 목록을 초기화한다.
- loading 중 기존 목록을 지우지 않는다.

### 3.2 지도

```http
GET /api/v1/map/info/viewport
```

요청 예:

```text
/api/v1/map/info/viewport?bbox=126.95,37.54,127.01,37.60&zoomLevel=7&category=ALL
```

`renderMode`에 따라 표시한다.

```text
REGION   시도/광역권 집계
DISTRICT 시군구/읍면동 집계
CLUSTER  지도 격자 클러스터
PLACE    개별 장소 marker
```

초기 Kakao level 기준:

| level | FE 렌더링 |
|---:|---|
| 1~5 | 개별 장소 또는 장소 cluster |
| 6~7 | 지도 cluster |
| 8~10 | 시군구·읍면동 집계 |
| 11 이상 | 시도·광역권 집계 |

## 4. FE 구현 작업

### 4.1 API client와 repository

기존 정보모드 호출을 다음 두 repository로 분리한다.

```ts
listInfoPlaces(input): Promise<InfoPlacePage>
loadMapViewport(input): Promise<MapViewportResponse>
```

수정 후보:

- `src/features/map/services/place.service.ts`
- `src/features/map/hooks/useMapData.ts`
- `src/features/map/hooks/useMapStore.ts`
- API client/type contract 파일

정보모드에서 category별 TourAPI 직접 호출은 제거한다. 장애 fallback이 필요하면 `degraded`를 명시한 BE 응답만 사용한다.

### 4.2 목록 상태

목록과 지도 상태를 분리한다.

```ts
category: MapInfoCategory
regionCode: string | null
listItems: PlaceListItem[]
listTotalCount: number
listNextCursor: string | null
listSnapshotId: string | null
isListLoading: boolean
listError: string | null
```

목록 scroll은 목록 API만 호출하고 viewport API를 호출하지 않는다.

### 4.3 지도 상태와 요청

```ts
viewportItems: ViewportItem[]
viewportRenderMode: 'REGION' | 'DISTRICT' | 'CLUSTER' | 'PLACE'
viewportSnapshotId: string | null
isViewportLoading: boolean
viewportError: string | null
```

현재 `idle + debounce` 흐름은 유지한다.

- 지도 이동 중 요청하지 않는다.
- idle 후 약 550ms debounce한다.
- `bbox`, `zoomLevel`, `category`, `regionCode`를 보낸다.
- 이전 응답은 새 응답이 올 때까지 유지한다.
- AbortController와 request sequence로 stale response를 무시한다.

수정 후보:

- `src/features/map/hooks/useKakaoMap.ts`
- `src/features/map/components/PlaceMarkers.tsx`
- cluster/aggregate renderer 신규 컴포넌트

### 4.4 전체 모드

- 왼쪽은 `전국 전체 장소 {totalCount}곳`으로 표시한다.
- 목록은 cursor pagination한다.
- broad zoom에서는 장소 marker를 렌더링하지 않는다.
- 지도는 `REGION → DISTRICT → CLUSTER → PLACE` 순서로 표시한다.
- cluster 클릭은 장소 상세가 아니라 지도 확대 또는 region scope 진입으로 처리한다.

### 4.5 뒤로가기와 breadcrumb

지원 URL:

```text
/map?mode=info&category=spot
/map?mode=info&category=all
/map?mode=info&category=all&regionCode=11
/map?mode=info&category=all&regionCode=11110
```

- category 변경과 region/cluster 선택은 history에 기록한다.
- zoom과 bbox 변경은 history에 기록하지 않는다.
- 목록 헤더에 `← 이전`을 둔다.
- `전국 > 서울특별시 > 종로구` breadcrumb을 제공한다.
- `전국으로 돌아가기`는 `regionCode`를 제거한다.

## 5. FE acceptance criteria

- 최초 정보모드 category가 `spot`이다.
- category 클릭 시 list와 viewport 요청이 각각 1회 발생한다.
- 목록 cursor scroll은 viewport API를 호출하지 않는다.
- 지도 idle 요청은 debounce 기간 내 1회만 발생한다.
- `ALL` broad zoom에서 REGION/DISTRICT/CLUSTER만 렌더링한다.
- cluster 클릭 후 region scope와 breadcrumb이 갱신된다.
- 브라우저 뒤로가기와 화면 내 back이 이전 상태를 복원한다.
- loading 중 기존 목록과 marker가 빈 화면으로 바뀌지 않는다.
- stale response가 최신 category 결과를 덮어쓰지 않는다.
- `totalCount`와 loaded item count가 구분된다.
- 정보모드에서 category별 TourAPI 직접 호출이 발생하지 않는다.

## 6. BE 의존성

FE 구현 전 BE가 다음을 동결해야 한다.

- OpenAPI schema
- list/viewport fixture
- category enum
- `renderMode`와 aggregate item schema
- cursor와 `snapshotId` 만료 오류
- `savedByMe`, `dataAvailability`, `coverage` 필드

BE가 준비되기 전에는 동일 fixture로 FE repository·상태·renderer를 병렬 구현한다.
