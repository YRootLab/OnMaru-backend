# FE 전달 보고서: 지도 정보모드 탐색·목록·클러스터 구현

## 1. 목적

상위 설계는 [공동 설계 스펙](../superpowers/specs/2026-09-29-map-info-exploration-design-공통설계스팩.md)이다.

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

## 3.2.1 지도 정보 밀도와 장소명 노출 정책

Kakao MarkerClusterer와 Naver MarkerClustering의 공통 패턴을 적용한다. 두 SDK 모두 줌 구간, grid size, 최소 cluster size, count 구간별 스타일을 기준으로 marker를 묶는다. 따라서 모든 줌에서 장소명을 노출하지 않고 지도에서 읽을 수 있는 정보량을 일정하게 유지한다.

### 줌 단계별 화면 규칙

| Kakao level | 지도 표현 | 장소명 | 숫자/지역명 | 사용자 행동 |
|---:|---|---|---|---|
| 1~4 | 상세 장소 | 선택·hover 장소만 표시 | 표시하지 않음 | marker 클릭 → 상세 |
| 5 | 장소 marker | 충돌하지 않는 최대 40개 | 표시하지 않음 | marker 클릭 → 상세 |
| 6 | 혼합 marker/소형 cluster | singleton 또는 선택 장소만 | 2개 이상 cluster count | cluster 클릭 → 1~2단계 확대 |
| 7 | 소형 cluster 중심 | 표시하지 않음 | cluster count | cluster 클릭 → child 범위 확대 |
| 8~9 | 읍·면·동/생활권 집계 | 표시하지 않음 | 지역명 + 장소 수 | 지역 집계 클릭 → 지역 진입 |
| 10 | 시군구 집계 | 표시하지 않음 | 시군구명 + 장소 수 | 시군구 클릭 → district scope |
| 11~12 | 시도/광역권 집계 | 표시하지 않음 | 시도명 + 장소 수 | 시도 클릭 → 하위 지역 확대 |
| 13~14 | 전국/광역권 요약 | 표시하지 않음 | 광역권 count | 광역권 클릭 → 확대 |

### 장소명 표시 규칙

- 영구 장소명 label은 Kakao level 5 이하에서만 허용한다.
- level 5에서도 화면 안 label은 최대 40개다.
- label 우선순위는 선택 장소, 현재 위치와 가까운 장소, 추천 장소, 나머지 장소 순이다.
- label 간 화면 픽셀 거리가 44px 미만이면 우선순위가 낮은 label을 숨긴다.
- level 6 이상에서는 기본적으로 장소명을 숨기고 category icon 또는 cluster count만 표시한다.
- 선택·hover·검색 결과 장소는 줌과 무관하게 임시 label을 표시할 수 있다.
- `ALL`에서는 중립 cluster를 사용하고, 내부 category 분포는 클릭 시 보여준다.
- 단일 장소 cluster는 숫자 `1`을 표시하지 않고 장소 marker로 표시한다.

### cluster count 시각 규칙

| count | 표현 |
|---:|---|
| 2~9 | 작은 원형 count badge |
| 10~49 | 중간 cluster badge |
| 50~199 | 큰 cluster badge |
| 200 이상 | 큰 badge + `200+` 축약 |

`200+`는 200개 이상이라는 의미이며, 정확한 수는 왼쪽 목록의 `totalCount` 또는 cluster 상세에서 확인한다.

### 지도 시각 요소 상한

- 하나의 viewport에서 동시에 보이는 시각 요소는 기본 60개 이하를 목표로 한다.
- 개별 marker가 60개를 초과하면 FE가 임의로 자르지 않고 BE cluster 응답으로 전환한다.
- label은 별도로 최대 40개다.
- cluster는 count 크기에 따라 3~4단계 스타일만 사용한다.
- 로딩 중에는 기존 marker/cluster를 유지하고 새 응답 도착 후 cross-fade한다.

### cluster 클릭 동작

- cluster 클릭은 즉시 장소 목록을 펼치지 않는다.
- FE는 cluster의 `bounds`로 fitBounds하거나 `targetZoomLevel`까지 1~2단계 확대한다.
- 확대 애니메이션이 끝난 뒤 최종 `idle` 이벤트에서 viewport API를 1회 호출한다.
- click handler와 idle handler가 중복 요청하지 않도록 동일 request key를 dedupe한다.
- 지역 aggregate 클릭도 같은 방식으로 동작한다.

### 지도와 왼쪽 목록의 분리

- 지도 marker/cluster는 호출 억제 정책에 따라 갱신한다.
- 왼쪽 목록은 category 전국 목록을 유지한다.
- 사용자가 지역 cluster를 클릭하거나 `이 지역 장소 보기`를 선택할 때만 region scope 목록으로 전환한다.
- region scope에 들어가면 제목과 breadcrumb을 `서울특별시 · 종로구 장소`처럼 변경한다.
- `전국으로 돌아가기`를 누르면 전국 목록과 전국 지도 집계로 돌아간다.

참고한 공식 SDK 패턴:

- Kakao MarkerClusterer: `minLevel`, `gridSize`, `minClusterSize`, `calculator`, `disableClickZoom`
  - https://apis.map.kakao.com/web/documentation/
- Naver MarkerClustering: `maxZoom`, `minClusterSize`, `gridSize`, count 구간별 `indexGenerator`
  - https://navermaps.github.io/maps.js.en/docs/tutorial-marker-cluster.example.html

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
