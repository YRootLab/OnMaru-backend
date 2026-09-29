# 지도 정보모드 탐색·목록·클러스터링 개선 스펙

## 문서 상태

- 문서 ID: `MAP-INFO-EXPLORATION-001`
- 작성일: 2026-09-29
- 상태: Design proposal
- 대상: OnMaru Frontend, OnMaru Backend
- 관련 이슈: `#375`, `#344`, `#486`

이 문서는 `/map` 정보모드에서 FE가 카테고리별로 한국관광공사 TourAPI를 직접 조합하지 않고, BE가 게시한 canonical 장소 snapshot을 기준으로 전국 목록·지역 탐색·지도 클러스터를 제공하기 위한 공동 계약이다.

전제:

- FE 정보모드 카테고리는 현재 8개다: `spot`, `experience`, `culture`, `festival`, `stay`, `food`, `cafe`, `market`.
- 사용자가 보는 category와 BE 내부 canonical category는 1:1로 같지 않다.
- BE는 제목 키워드가 아니라 TourAPI 원천 분류 코드와 승인된 mapping policy로 관련 category를 확장한다.
- TourAPI는 사용자 요청 시 호출하지 않는다. 수집·정규화·검증·게시된 catalog snapshot만 공개 조회한다.
- `전체`는 지원하지만 기본 선택값은 `spot`이며 화면 표시명은 `고택`이다.

## 1. 현재 구현 근거와 문제

### 1.1 FE

- `src/features/map/hooks/useMapData.ts`가 지도 중심·반경·선택 category로 `/api/map/places`를 호출한다.
- `src/features/map/hooks/useKakaoMap.ts`는 Kakao Map `idle` 이후 약 550ms debounce를 거쳐 줌 변경 또는 중심 이동을 재조회한다.
- `src/features/map/services/place.service.ts`는 category가 없을 때만 BE 조회를 우선한다.
- category가 선택되면 FE가 TourAPI operation을 여러 번 호출하는 fallback 경로를 사용한다.
- `src/features/map/components/PlaceMarkers.tsx`는 전달받은 장소를 클라이언트에서 표시하며, 서버 지역 집계·클러스터 계약은 없다.

### 1.2 BE

현재 `/api/v1/map/places`와 `/api/map/places`는 `MapPlaceQueryService`의 active published snapshot을 조회한다.

- `bbox`, 중심 좌표·반경, `regionCode`, 단일 `category`, `limit`을 지원한다.
- `limit`은 1~1000 범위다.
- 응답은 장소 카드 목록이며 줌 레벨별 지역 집계나 클러스터를 반환하지 않는다.
- snapshot을 메모리 stream으로 필터링·정렬·limit 처리하므로 전국 3만 건과 반복 줌 조회를 위한 read model/index 요구사항이 필요하다.

### 1.3 해결 대상

- FE가 category마다 여러 TourAPI 요청을 수행한다.
- `고택`처럼 데이터가 적은 category가 관련 장소를 충분히 포함하지 못한다.
- `전체` 선택 시 전국 장소 전체를 지도에 한 번에 내리면 응답 크기와 marker 렌더링 비용이 커진다.
- 지도 축소 시 장소 원본을 계속 늘려 받는 대신 시도·시군구·읍면동·cluster 집계가 필요하다.
- category 또는 지역을 선택한 후 이전 목록으로 돌아갈 명시적 상태와 URL 계약이 없다.

## 2. 사용자 경험 목표와 범위

### 2.1 목표

- 정보모드 진입 시 `고택`이 자동 선택되고, 고택 계열의 충분한 장소가 목록과 지도에 표시된다.
- 왼쪽 목록은 전체 개수와 cursor pagination을 제공한다.
- `전체`를 선택하면 전국 데이터를 탐색하되 지도는 줌에 따라 집계·cluster·장소로 단계적으로 내려간다.
- category 또는 지역 변경 후 브라우저 뒤로가기와 화면 내 뒤로가기로 이전 목록을 복원한다.
- 지도 이동 중에는 요청하지 않고 이동 종료 후 한 번만 조회한다.
- 원천 데이터가 부족한 category는 BE의 승인된 연관 category 확장으로 보완한다.

### 2.2 범위

포함: 8개 category와 `ALL`, 전국/지역 cursor 목록, viewport aggregate/cluster/place API, category mapping·dedupe, URL/history, DB·성능·관측성·테스트 요구사항.

제외: 장소 상세 전체 개편, 개인화 추천 ranking, 실시간 위치 추적, 축제 운영일정 모델 자체, Redis/Kafka/Kubernetes 도입.

## 3. 정보모드 상태 모델

### 3.1 기본 상태

```text
mode       = info
category   = spot
regionCode = null
depth      = nationwide
```

`spot`은 FE에서 `고택`으로 표시하고, BE는 고택·역사·문화 장소 묶음으로 확장한다.

### 3.2 상태 전이

```text
정보모드 진입 → 고택(spot) 기본 목록 + 고택 계열 지도
category 선택 → 해당 category 전국 목록 첫 페이지 + 동일 category 지도
전체 선택 → 전국 전체 목록 첫 페이지 + 지도 region aggregate
지역 aggregate/cluster 선택 → regionCode/bbox를 추가한 하위 탐색
뒤로가기 → 이전 category/region 상태 복원
```

### 3.3 URL 상태

```text
/map?mode=info&category=spot
/map?mode=info&category=all
/map?mode=info&category=all&regionCode=11
/map?mode=info&category=all&regionCode=11110
```

- category 변경과 지역 aggregate/cluster 선택은 history에 기록한다.
- zoom level, bbox, cursor는 history에 기록하지 않는다.
- 목록 헤더에 `← 이전`과 breadcrumb을 제공한다.
- `전국으로 돌아가기`는 `regionCode`를 제거하고 `category=all`을 유지한다.

## 4. FE category와 BE canonical mapping

mapping은 제목 검색이 아니라 원천 분류 코드와 dataset revision에 버전으로 묶는다.

| FE category | 표시명 | BE 내부 기본 범위 | 정책 |
|---|---|---|---|
| `spot` | 고택 | `HANOK`, `HISTORIC_SITE`, 검수된 `CULTURE_ART`, 한옥마을·고택 | 기본 선택 |
| `experience` | 전통 체험 | `HANOK_EXPERIENCE`, 검수된 `LOCAL_SCENE` | 제목 키워드 추정 금지 |
| `culture` | 문화유산 | `HISTORIC_SITE`, `CULTURE_ART`, 서원·향교·사찰 승인 범위 | `spot`과 중복 가능 |
| `festival` | 축제 | 별도 `EVENT` projection | 장소 snapshot과 분리 |
| `stay` | 한옥 숙소 | `HANOK_STAY`, 승인된 전통 숙박 | 일반 숙박 자동 포함 금지 |
| `food` | 전통 맛집 | `TRADITIONAL_FOOD`, 승인된 한식·향토음식·노포 | 일반 음식점 전체 자동 포함 금지 |
| `cafe` | 한옥 카페 | `HANOK_CAFE`, 전통찻집, 승인된 카페 | source code 검수 후 포함 |
| `market` | 전통 시장 | `TRADITIONAL_MARKET`, 장터, 승인된 시장 인접 상점 | 공방·서점은 별도 승인 |
| `all` | 전체 | 공개 가능한 모든 canonical place | event 포함 여부 명시 |

같은 place가 여러 canonical category에 매핑되어도 응답에서는 `placeId` 기준 1건만 반환한다. `matchedCategories`로 매칭 근거를 추적할 수 있게 한다.

`festival`은 날짜·운영 기간·종료 상태가 있으므로 일반 장소 count에 강제로 합치지 않는다. 지도에 포함할 경우 `type=EVENT`로 명시한다.

## 5. BE API 계약

### 5.0 Kakao Map SDK와 BE의 책임 경계

이 설계는 FE가 사용하는 Kakao Map JavaScript SDK의 현재 동작을 전제로 한다.

- FE Kakao SDK가 지도 중심, 현재 bounds, Kakao `level`을 관리한다.
- FE는 `map.getBounds()`에서 `minLng,minLat,maxLng,maxLat`를 계산해 BE에 전달한다.
- FE는 `map.getLevel()`을 `zoomLevel`로 전달한다. Kakao Map은 level 숫자가 낮을수록 확대 상태다.
- BE는 Kakao Map SDK나 Kakao 장소 검색 API를 사용자 요청마다 호출하지 않는다.
- BE는 TourAPI에서 정규화·게시한 WGS84 경위도 좌표와 PostGIS를 사용해 bbox·region·cluster를 계산한다.
- bbox는 `lng,lat,lng,lat`, 장소 좌표 객체는 `{ lat, lng }` 순서를 계약으로 고정한다.
- Kakao marker 표시, bounds fit, cluster 클릭 후 확대는 FE 책임이다.

기존 중심점·반경 요청은 호환 기간 동안 유지할 수 있지만, 새 viewport 계약의 정식 입력은 `bbox + zoomLevel`이다.

### 5.1 전국·지역 목록

```http
GET /api/v1/map/info/places
```

Query:

| 이름 | 타입 | 기본/제약 | 설명 |
|---|---|---|---|
| `category` | enum | `SPOT`; `ALL`, `SPOT`, `EXPERIENCE`, `CULTURE`, `FESTIVAL`, `STAY`, `FOOD`, `CAFE`, `MARKET` | FE 표시 category |
| `regionCode` | string | optional | 시도·시군구·읍면동 코드 |
| `bbox` | string | optional | `minLng,minLat,maxLng,maxLat` |
| `cursor` | opaque string | optional | `nextCursor` 재전달 |
| `limit` | integer | 30, max 100 | page size |
| `sort` | enum | `REGION_NAME` | `DISTANCE`, `REGION_NAME`, `NAME` |
| `lat`,`lng` | number | distance sort에서 required | 기준점 |
| `snapshotId` | string | optional | 페이지 기준 revision 고정 |
| `language` | string | `ko-KR` | 응답 언어 |

응답:

```json
{
  "schemaVersion": "1.0",
  "query": { "category": "SPOT", "regionCode": null, "language": "ko-KR" },
  "snapshot": { "id": "rev-20260929-001", "publishedAt": "2026-09-29T03:10:00Z" },
  "totalCount": 1284,
  "items": [
    {
      "placeId": "p-123",
      "name": "예시 고택",
      "displayCategory": "spot",
      "matchedCategories": ["HANOK", "HISTORIC_SITE"],
      "region": { "regionCode": "11110", "name": "서울특별시 종로구" },
      "coordinates": { "lat": 37.58, "lng": 126.98 },
      "thumbnailUrl": null,
      "summary": "...",
      "distanceMeters": null,
      "savedByMe": false,
      "dataAvailability": { "place": "COMPLETE", "image": "PARTIAL", "detail": "PARTIAL" }
    }
  ],
  "nextCursor": "opaque-cursor-or-null",
  "appliedCategories": ["HANOK", "HISTORIC_SITE", "CULTURE_ART"],
  "coverage": "COMPLETE"
}
```

목록 규칙:

- `totalCount`는 raw row 수가 아니라 mapping 적용·dedupe 후 공개 canonical place 수다.
- cursor는 offset이 아닌 keyset cursor이며 `snapshotId`, category, mapping version, sort key, 마지막 place key를 opaque/signature로 포함한다.
- snapshot이 바뀌었는데 `snapshotId`가 없으면 새 snapshot으로 시작한다.
- 만료 cursor는 `409 SNAPSHOT_EXPIRED`와 새 snapshot 정보를 반환한다.
- 공개 불가·좌표 결측·quarantine 장소는 지도 목록에서 기본 제외한다.
- thumbnail/detail 누락은 장소 목록 실패가 아니며 `dataAvailability`로 표시한다.

### 5.2 지도 viewport

```http
GET /api/v1/map/info/viewport
```

Query:

| 이름 | 타입 | 기본/제약 | 설명 |
|---|---|---|---|
| `bbox` | string | required | `minLng,minLat,maxLng,maxLat` |
| `zoomLevel` | integer | required | Kakao level; 낮을수록 확대 |
| `category` | enum | `SPOT` | 목록 API와 동일 |
| `regionCode` | string | optional | 선택된 지역 scope |
| `snapshotId` | string | optional | 목록과 동일 revision |
| `limit` | integer | 500, max 1000 | item 상한 |
| `language` | string | `ko-KR` | 응답 언어 |

초기 zoom profile:

| Kakao level | `renderMode` | 반환 |
|---:|---|---|
| 1~5 | `PLACE` | 개별 장소, 최대 500/1000건; 초과 시 cluster 전환 |
| 6~7 | `CLUSTER` | 지도 격자 cluster와 count |
| 8~10 | `DISTRICT` | 시군구·읍면동 aggregate |
| 11 이상 | `REGION` | 시도·광역권 aggregate |

초기 임계값은 FE 현재 `LABEL_MAX_LEVEL=5`, `PIN_MAX_LEVEL=6`을 기준으로 두고, 운영 지표로 조정한다. 응답에는 `profileVersion`을 포함한다.

응답 예:

```json
{
  "schemaVersion": "1.0",
  "renderMode": "CLUSTER",
  "profileVersion": "map-zoom-v1",
  "snapshot": { "id": "rev-20260929-001", "publishedAt": "2026-09-29T03:10:00Z" },
  "category": "ALL",
  "totalCountInViewport": 4830,
  "items": [
    {
      "type": "CLUSTER",
      "clusterId": "z7-x104-y52",
      "center": { "lat": 37.57, "lng": 126.98 },
      "count": 184,
      "categoryCounts": { "SPOT": 80, "FOOD": 48, "CAFE": 36, "MARKET": 20 },
      "bounds": { "west": 126.95, "south": 37.54, "east": 127.01, "north": 37.60 }
    }
  ],
  "appliedCategories": ["HANOK", "HISTORIC_SITE", "TRADITIONAL_FOOD", "HANOK_CAFE", "TRADITIONAL_MARKET"],
  "coverage": "COMPLETE"
}
```

`items.type`은 `REGION`, `DISTRICT`, `CLUSTER`, `PLACE` 중 하나다. aggregate item에는 `regionCode`, `name`, `count`, `center`, `bounds`, `categoryCounts`를 제공한다. cluster 클릭은 장소 상세가 아니라 해당 bounds fit 또는 region scope 재조회로 처리한다.

### 5.2.1 지도 정보 밀도 정책

FE는 장소명을 모든 줌에서 표시하지 않는다.

- level 1~4: 선택·hover 장소명만 표시
- level 5: 충돌하지 않는 label 최대 40개
- level 6: singleton marker와 소형 cluster 혼합
- level 7: count cluster 중심
- level 8~9: 읍·면·동/생활권 집계
- level 10: 시군구 집계
- level 11~12: 시도·광역권 집계
- level 13~14: 전국/광역권 요약

하나의 viewport에서 시각 요소는 60개 이하를 목표로 하며, cluster count는 2~9, 10~49, 50~199, 200+ 구간으로 표시한다. 장소명 label 충돌 회피와 선택·hover 예외는 FE가 담당하고, BE는 `renderMode`, `count`, `bounds`, `targetZoomLevel`, `servedBbox`를 제공한다.

cluster 또는 지역 aggregate 클릭은 즉시 목록을 펼치는 것이 아니라 bounds fit/zoom 이동 후 최종 idle에서 viewport를 한 번 조회한다. 왼쪽 목록은 사용자가 `이 지역 장소 보기` 또는 지역 cluster를 선택한 경우에만 전국 scope에서 지역 scope로 전환한다.

### 5.3 기존 API 호환

- 기존 `/api/v1/map/places`, `/api/map/places`는 새 API 전환 동안 유지한다.
- 새 API가 안정화되면 legacy endpoint에 deprecation header와 종료 계획을 추가한다.
- production 정보모드에서는 category별 FE TourAPI 직접 조합 fallback을 제거한다.
- 장애 fallback은 `degraded=true`, `stale=true`, `asOf`를 명시하는 경우에만 허용한다.

## 6. FE 구현 요구사항

### 6.1 API client와 상태 분리

FE는 목록과 지도 repository를 분리한다.

```ts
listInfoPlaces(input): Promise<InfoPlacePage>
loadMapViewport(input): Promise<MapViewportResponse>
```

두 응답을 하나의 `items` 배열에 합치지 않는다. 지도 재조회가 왼쪽 목록을 지우거나 목록 pagination이 지도 marker를 바꾸면 안 된다.

최소 상태:

```ts
type MapInfoState = {
  category: MapInfoCategory; // default: 'spot'
  regionCode: string | null;
  breadcrumb: BreadcrumbItem[];
  listItems: PlaceListItem[];
  listTotalCount: number;
  listNextCursor: string | null;
  listSnapshotId: string | null;
  viewportItems: ViewportItem[];
  viewportRenderMode: 'REGION' | 'DISTRICT' | 'CLUSTER' | 'PLACE';
  viewportSnapshotId: string | null;
  isListLoading: boolean;
  isViewportLoading: boolean;
  listError: string | null;
  viewportError: string | null;
};
```

### 6.2 요청 시점

- 최초 진입: `listInfoPlaces(category=spot)`와 `loadMapViewport(category=spot)`을 병렬 호출한다.
- category 변경: list cursor를 초기화하고 두 API를 병렬 호출한다.
- 목록 sentinel 도달: `listInfoPlaces(cursor=nextCursor)`만 호출한다.
- 지도 idle: `loadMapViewport(bbox, zoomLevel, category, regionCode)`만 호출한다.
- aggregate/cluster 클릭: URL scope를 갱신하고 목록 첫 페이지와 viewport 하위 범위를 병렬 호출한다.
- 장소 선택: 기존 detail API만 호출한다.

### 6.2.1 호출 억제 정책

FE는 지도 `idle` 이벤트마다 BE를 호출하지 않는다.

- 사용자 위치가 없으면 초기 Kakao level은 9, 사용자 위치가 있으면 7로 시작한다.
- 초기 category는 `spot`이며, 최초 왼쪽 목록은 전국 category 목록이다.
- 최초 목록은 `totalCount`와 첫 page 30건을 보여준다.
- category 변경 시 현재 중심·level을 유지한 채 list와 viewport를 각각 1회 조회한다.
- 왼쪽 목록은 지도 이동만으로 바꾸지 않는다. region/cluster 클릭으로 하위 scope에 들어갈 때만 지역 목록을 조회한다.
- FE는 실제 viewport보다 각 방향으로 25% 확장한 bbox를 요청한다.
- viewport 응답의 `servedBbox` 안에 현재 viewport가 완전히 포함되고 category·snapshot·render bucket이 같으면 재호출하지 않는다.
- current viewport가 servedBbox 경계 밖으로 20% 이상 벗어나거나 render bucket이 바뀔 때만 호출한다.
- 이동 종료 후 650ms debounce하고, 동일 scope에는 하나의 in-flight request만 유지한다.
- 작은 이동은 기존 viewport 응답을 유지한다. 명시적 재검색 버튼은 예외로 즉시 요청한다.

현재 FE의 약 550ms debounce를 유지할 수 있다. 모든 viewport 요청에는 AbortController 또는 동등한 cancellation을 적용하고, 늦게 도착한 이전 응답이 최신 category/region 결과를 덮어쓰지 못하도록 request sequence를 검사한다.

### 6.3 목록 UX

- 기본 헤더: `고택 목록`
- category 헤더: `{category label} 목록`
- 전체 헤더: `전국 전체 장소 {totalCount}곳`
- 지역 scope: `서울특별시 · 종로구 장소 {totalCount}곳`
- loading 중 기존 목록은 유지하고 하단 skeleton만 추가한다.
- 초기 실패 시 기존 목록을 지우지 않고 재시도 버튼을 표시한다.
- 빈 결과에는 category/지역 초기화 CTA를 제공한다.
- `totalCount`와 현재 로드된 item 수를 구분한다.

### 6.4 지도 UX

- `REGION`/`DISTRICT`: 숫자 badge 또는 지역 label을 표시한다.
- `CLUSTER`: count와 category 분포를 표시하되 장소명 목록으로 오인시키지 않는다.
- `PLACE`: 현재 FE category pin style을 재사용한다.
- aggregate mode로 전환되면 개별 marker를 제거하고 aggregate item을 표시한다.
- 새 응답 전까지 이전 viewport를 유지해 빈 지도 깜빡임을 막는다.
- 새 viewport가 비어 있으면 지도는 유지하고 `현재 화면에 장소가 없어요`를 표시한다.
- `ALL`은 broad zoom에서 반드시 aggregate/cluster만 허용한다.

## 7. BE 데이터·쿼리·성능

### 7.1 조회 source

- 모든 API는 active published catalog revision을 기준으로 조회한다.
- raw TourAPI table을 web request에서 직접 조회하지 않는다.
- category mapping version은 catalog revision metadata와 함께 저장한다.
- 공개 불가·좌표 결측·quarantine·삭제 상태는 기본 제외한다.

### 7.2 read model과 index

현재 catalog projection을 재사용하되 다음 조회를 지원해야 한다.

- `place_id`, `revision_id`, `status`, `category`, `region_id`, `location`, `name`
- `place_id` dedupe
- region hierarchy lookup
- 좌표와 region count 기반 aggregate

필수 검토 index:

```sql
-- 실제 컬럼명은 canonical schema naming에 맞춰 조정한다.
CREATE INDEX ... ON catalog_place_versions (revision_id, status, category, region_id, place_id);
CREATE INDEX ... ON catalog_place_versions USING GIST (location);
CREATE INDEX ... ON catalog_place_versions (revision_id, status, name, place_id);
```

기존 index가 active revision predicate를 충분히 지원하지 못하면 partial/materialized read projection을 검토한다. 별도 Redis나 타일 서버는 첫 구현의 필수 조건이 아니다.

### 7.3 cluster 계산

초기 구현은 PostGIS와 zoom별 deterministic grid를 사용한다.

- `zoomLevel`에서 grid precision을 결정한다.
- 동일 grid cell을 `count`, `categoryCounts`, centroid, bounds로 집계한다.
- `clusterId`는 snapshot·zoom·grid cell에 대해 deterministic해야 한다.
- `PLACE` 상한을 넘으면 임의로 일부 장소와 일부 cluster를 섞지 말고 일관된 cluster 응답으로 전환한다.
- region aggregate는 행정구역 경계와 canonical region table을 사용한다.
- 단순 위경도 반올림으로 행정구역을 추정하지 않는다.

### 7.4 제한값

- 목록 `limit`: 기본 30, 최대 100
- viewport `PLACE` limit: 기본 500, 최대 1000
- aggregate/cluster item limit: 500
- 비정상적인 전 지구 bbox는 `400 INVALID_REQUEST`
- 요청 timeout: 2초 목표, 5초 hard timeout

제한에 도달하면 실패 대신 `coverage=PARTIAL`, `hasMore=true` 또는 `renderMode=CLUSTER`로 전환한다.

## 8. 오류·부분 실패·캐시

### 8.1 오류 계약

- 잘못된 category, bbox, zoomLevel, limit: `400 INVALID_REQUEST`
- cursor snapshot 불일치/만료: `409 SNAPSHOT_EXPIRED`
- active snapshot 없음: `503 CATALOG_UNAVAILABLE`
- 로그인 세션 오류는 public 목록 실패로 처리하지 않고 `savedByMe=false`로 degrade할 수 있다.

### 8.2 부분 실패

- thumbnail/detail 누락은 장소 목록 실패가 아니다.
- category mapping 일부가 게시되지 않았으면 `coverage=PARTIAL`과 `appliedCategories`를 반환한다.
- DB read timeout은 stale cache/LKG를 사용할 수 있지만 `stale=true`, `asOf`를 포함한다.
- TourAPI 장애는 게시된 snapshot 조회에 영향을 주지 않는다.

### 8.3 캐시

- public 응답 key에 snapshotId, category, regionCode/bbox, zoom profile을 포함한다.
- 사용자별 `savedByMe` 응답은 public cache와 분리한다.
- 초기에는 application local cache 또는 HTTP cache-control로 시작할 수 있다.
- snapshot 게시 시 versioned key로 자연스럽게 무효화한다.

## 9. 관측성

구조화 로그와 metric label에 다음을 남긴다.

- `requestId`, `snapshotId`, `category`, `appliedCategories`
- `regionCode`, `zoomLevel`, `renderMode`
- `bboxHash`, `limit`, `itemCount`, `totalCount`
- `queryDurationMs`, `cacheHit`, `coverage`, `stale`
- cursor hash

권장 metrics:

- `map_info_list_requests_total`
- `map_info_viewport_requests_total`
- `map_info_query_duration_ms`
- `map_info_partial_response_total`
- `map_info_snapshot_expired_total`
- `map_info_items_returned`

알림 후보:

- viewport query p95가 1초 초과
- 5분간 `503` 비율이 1% 초과
- `PARTIAL` 응답 비율 급증
- `ALL` 조회에서 place response 상한 초과 반복

## 10. 검증 계획과 acceptance criteria

### 10.1 FE

- 정보모드 최초 category가 `spot`이다.
- category 선택 시 list와 viewport 요청이 각각 한 번씩 발생한다.
- 목록 cursor scroll은 viewport API를 호출하지 않는다.
- map idle 재조회는 debounce 기간 내 한 번만 발생한다.
- 늦은 이전 요청이 최신 category/region 결과를 덮어쓰지 않는다.
- `ALL` broad zoom에서 cluster/region item만 렌더링한다.
- cluster 클릭 후 region scope와 breadcrumb이 갱신된다.
- browser back과 화면 내 back이 이전 category/region 목록을 복원한다.
- loading 중 기존 list item이 사라지지 않는다.
- `totalCount`와 로드된 item 수가 분리 표시된다.

### 10.2 BE

- 각 FE category가 승인된 canonical category 집합으로 확장된다.
- 같은 place가 여러 mapping에 걸려도 응답에 한 번만 나온다.
- `totalCount`가 raw row가 아닌 dedupe 후 공개 place 수와 일치한다.
- cursor가 같은 snapshot에서 중복·누락 없이 모든 page를 순회한다.
- snapshot 변경 시 이전 cursor가 `409 SNAPSHOT_EXPIRED`가 된다.
- `ALL`이 category filter 없이 공개 canonical 장소를 반환한다.
- bbox와 regionCode가 동시에 적용될 때 교집합이 반환된다.
- 각 zoom profile이 예상 `renderMode`를 반환한다.
- cluster count 합계가 viewport 공개 장소 수와 일치한다.
- 좌표 결측·비공개·quarantine 장소가 지도에서 제외된다.
- `festival` event가 일반 place count에 잘못 합쳐지지 않는다.
- 세션이 없어도 public 응답이 성공하고 `savedByMe=false`가 된다.

### 10.3 성능·장애

- 공개 snapshot 3만 건 기준 `ALL` count와 첫 page p95를 측정한다.
- 전국·시도·시군구·상세 bbox를 각각 측정한다.
- 동시 viewport 요청과 동일 cache key 반복 요청을 측정한다.
- DB slow query와 active snapshot 교체 중 cursor 조회를 검증한다.
- TourAPI down에서도 게시된 snapshot API가 정상 동작한다.
- cache unavailable 시 DB fallback이 동작한다.
- FE 요청 취소와 stale response 무시를 E2E에서 검증한다.

## 11. 구현 순서

1. category mapping 정책과 revision metadata 확정
2. active catalog list projection/index 검토
3. `/api/v1/map/info/places` contract와 fixture 작성
4. category mapping·dedupe·cursor 통합 테스트 작성
5. `/api/v1/map/info/viewport` region/cluster/place read model 작성
6. zoom profile과 PostGIS grid 집계 검증
7. FE API repository와 store 분리
8. FE 기본 category·전체 모드·breadcrumb/history 적용
9. FE marker/cluster renderer 전환
10. legacy `/map/places` fallback 제거 및 운영 metric 확인

## 12. 완료 정의

- FE 정보모드가 category별 TourAPI 직접 조합 없이 BE API만 사용한다.
- 기본 진입은 `고택(spot)`이고 8개 category와 `전체`가 동작한다.
- 왼쪽 목록은 `totalCount + cursor`로 전국 및 지역 목록을 탐색한다.
- `전체` 지도는 `REGION → DISTRICT → CLUSTER → PLACE`로 전환된다.
- category/지역 선택 후 browser back, breadcrumb, 화면 내 back이 동작한다.
- 동일 장소 중복이 없고 mapping 결과가 응답으로 추적된다.
- 3만 건 공개 snapshot에서 전국·시군구 latency, limit, partial behavior가 검증된다.
- OpenAPI, fixture, FE contract test, BE integration test가 같은 필드를 사용한다.
- TourAPI 장애 시 사용자 요청 경로가 외부 API에 연쇄 호출되지 않는다.
