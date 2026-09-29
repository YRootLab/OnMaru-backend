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

### Kakao Map SDK 연동 규칙

- FE는 Kakao Map SDK에서 `map.getBounds()`를 읽어 `bbox=minLng,minLat,maxLng,maxLat`로 전송한다.
- FE는 `map.getLevel()`을 `zoomLevel`로 전송한다. Kakao level은 숫자가 낮을수록 확대 상태다.
- 지도 이동·확대·축소가 끝난 뒤 viewport API를 호출한다.
- marker 표시, cluster 클릭 후 지도 확대, bounds fit은 FE가 수행한다.
- BE는 Kakao 지도 SDK나 Kakao 장소 검색 API를 호출하지 않는다.
- 장소 좌표는 BE가 제공하는 WGS84 `{ lat, lng }`를 사용한다.
- bbox 문자열은 `lng,lat,lng,lat` 순서이며, 장소 좌표 객체는 `{ lat, lng }` 순서다.

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

## 3.3 초기 지도와 목록 범위 정책

현재 FE의 `DEFAULT_LEVEL=11`은 전국에 가까운 넓은 범위를 보여주므로, 정보모드 초기 탐색에서는 다음 기본값으로 조정한다.

| 상황 | 초기 중심 | 초기 Kakao level | viewport 응답 |
|---|---|---:|---|
| 사용자 위치 없음 | 현재 fallback center `36.35,127.75` | `9` | 시군구·읍면동 집계 |
| 사용자 위치 있음 | 사용자 위치 | `7` | cluster 중심 |

- 초기 category는 항상 `spot`이다.
- 최초 왼쪽 목록은 현재 지도 viewport와 무관한 전국 `SPOT` 목록이다.
- 최초 목록은 `totalCount`와 첫 page 30건을 표시한다.
- 사용자가 지도를 옆으로 조금 이동하는 것만으로 왼쪽 목록을 바꾸지 않는다.
- 지역 aggregate/cluster를 클릭해 하위 탐색에 들어간 경우에만 왼쪽 목록을 해당 region scope로 바꾼다.
- `전국으로 돌아가기`를 누르면 전국 category 목록으로 복귀한다.

category 탭을 누를 때는 현재 지도 중심과 level을 유지한다. category만 바꿔 viewport API를 1회 호출하고, 왼쪽 목록은 선택 category의 전국 첫 page를 별도로 1회 호출한다.

## 3.4 viewport 호출 억제 정책

FE는 단순히 `idle`이 발생할 때마다 BE를 호출하지 않는다. viewport 응답마다 `servedBbox`와 `renderMode`를 저장하고, 다음 조건에서만 새 요청을 보낸다.

### 호출하지 않는 경우

- 지도 이동 중
- idle 후 debounce 이전
- 현재 `bbox`가 동일 category·snapshot·render bucket의 `servedBbox` 안에 완전히 포함되는 경우
- 같은 render bucket 안에서 작은 이동만 발생한 경우
- 목록 sentinel이 다음 cursor를 요청하는 경우

### 호출하는 경우

- 최초 정보모드 진입
- category 변경
- Kakao zoom level이 render bucket을 변경한 경우
- 현재 viewport가 `servedBbox`의 가장자리에서 20% 이상 벗어난 경우
- cluster/region을 클릭해 하위 region scope로 들어간 경우
- 사용자가 명시적으로 `현재 지도에서 다시 검색`을 누른 경우

### fetch gate 계산

```text
renderBucket = REGION | DISTRICT | CLUSTER | PLACE
cacheKey = snapshotId + category + regionScope + renderBucket

if currentViewport ⊆ servedBbox and same cacheKey:
  no fetch
else if viewport가 servedBbox 경계 밖으로 20% 미만 이동:
  no fetch
else:
  fetch
```

요청은 지도 `idle` 후 650ms debounce한다. 같은 시점에는 category·region·viewport별 요청을 하나만 유지하고, 이전 요청은 AbortController로 취소한다.

FE는 실제 viewport보다 각 방향으로 25% 확장한 `requestBbox`를 요청한다. 이렇게 하면 작은 이동에는 기존 응답을 재사용할 수 있다. 확장된 범위가 대한민국 전체를 넘지 않도록 clamp한다.

### 확대·축소 처리

- 같은 render bucket 안에서 확대·축소하면 `servedBbox`가 현재 viewport를 포함하는 한 재요청하지 않는다.
- `REGION → DISTRICT → CLUSTER → PLACE`처럼 render bucket이 바뀌면 반드시 재요청한다.
- zoom out으로 더 넓은 bucket이 되면 개별 장소를 재사용하지 않고 aggregate 응답을 새로 받는다.
- zoom in으로 더 상세한 bucket이 되면 현재 viewport 중심의 확장 bbox를 새로 요청한다.

### 지도 이동과 목록의 분리

```text
지도 이동/확대/축소
  → viewport API만 호출

왼쪽 목록 scroll
  → places API의 nextCursor만 호출

cluster/region 클릭
  → URL regionCode 변경
  → places 첫 page + viewport 하위 범위 호출
```

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
- 실제 viewport보다 25% 확장한 `requestBbox`를 보낸다.
- 이전 응답은 새 응답이 올 때까지 유지한다.
- AbortController와 request sequence로 stale response를 무시한다.
- 응답의 `servedBbox`를 캐시 coverage로 저장한다.

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
- 작은 pan으로 current viewport가 servedBbox 안에 있으면 viewport 요청이 발생하지 않는다.
- servedBbox를 20% 이상 벗어나면 viewport 요청이 발생한다.
- render bucket이 바뀌면 viewport 요청이 발생한다.
- 이동 중에는 요청하지 않고 idle 후 650ms debounce 뒤 요청한다.
- 실제 viewport보다 25% 확장된 requestBbox를 사용한다.

## 6. BE 의존성

FE 구현 전 BE가 다음을 동결해야 한다.

- OpenAPI schema
- list/viewport fixture
- category enum
- `renderMode`와 aggregate item schema
- cursor와 `snapshotId` 만료 오류
- `savedByMe`, `dataAvailability`, `coverage` 필드

BE가 준비되기 전에는 동일 fixture로 FE repository·상태·renderer를 병렬 구현한다.
