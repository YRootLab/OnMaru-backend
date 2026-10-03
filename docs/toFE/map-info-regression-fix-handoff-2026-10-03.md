# 지도 정보모드 데이터 회귀 수정 FE 전달서

> 기준일: 2026-10-03
> 수신: OnMaru Frontend
> 관련 FE 작업: Issue #246, PR #250, PR #275
> 관련 BE 작업: Issue #493, #553, #566
> 상태: BE 코드 수정 완료, staging·운영 배포 확인 전 운영 전환 금지

## 전달 결론

운영 `/map` 정보모드는 신규 `/api/v1/map/info/places`와
`/api/v1/map/info/viewport` 대신 구형 `/api/map/places`를 사용하고 있다. 구형 API는
최대 100건만 반환하고, FE가 그 표본을 다시 거리·카테고리로 거르므로 활성 지도
카탈로그 23,675건 중 일부만 화면에 나타난다.

FE는 PR #250에서 구현했던 목록·viewport 분리 구조를 다시 연결해야 한다. 다만 현재
BE 코드는 Issue #566 브랜치에서 두 번째 page cursor와 지도 `HANOK` category를 수정했다.
다만 staging·운영 배포가 확인되기 전에는 운영 트래픽을 신규 경로로 전환하지 않는다.

## 현재 운영 증상과 원인

| 증상 | 직접 원인 | 책임 경계 |
| --- | --- | --- |
| 전체 장소가 100건 이하로 보임 | `useMapData`가 legacy `/map/places`를 호출하고 BE adapter가 100건으로 제한 | FE 주원인, BE legacy 정책 |
| 반경에 따라 64·77·87건처럼 더 줄어듦 | 이미 잘린 첫 100건을 FE가 거리로 다시 필터링 | FE |
| 한옥 칩 결과가 적거나 부정확함 | `hanok`이 API route에서 무효 category로 제거되고 첫 100건에 `isHanok()` 문자열 필터 적용 | FE·계약 |
| 신규 목록 두 번째 page 실패 | signed cursor payload의 `0.0`과 `.` 구분자가 충돌 | BE |
| 신규 API에 `category=HANOK` 전송 시 400 | 지도 category enum에 `HANOK`이 없음 | BE 계약 |

상세 진단과 재현 명령은
[`troubleshooting-worklog/26.10.03 map-info-100-item-and-hanok-filter-regression.md`](../../troubleshooting-worklog/26.10.03%20map-info-100-item-and-hanok-filter-regression.md)를 참고한다.

## FE에서 제거하거나 중단할 경로

정보모드에서는 아래 구형 데이터 흐름을 사용하지 않는다.

```text
MapPage
  → useMapData
  → /api/map/places
  → PlaceService.getNearbyPlaces
  → legacy backend 또는 TourAPI 직접 호출
  → PlaceList의 클라이언트 필터와 페이지네이션
```

구체적인 변경 대상은 다음과 같다.

### `src/features/map/MapPage.tsx`

- 정보모드 데이터 조회에 `useInfoMapData()`를 다시 연결한다.
- `useMapData()`는 온기모드 또는 구형 경로가 꼭 필요한 모드에서만 실행한다.
- `ViewportOverlays`를 다시 렌더링한다.
- category·regionCode URL 동기화를 복구하되 초기 URL hydration과 history 갱신이
  서로 반복되지 않도록 기존 request key와 함께 검증한다.

### `src/features/map/hooks/useMapData.ts`

- `mode === 'info'`이면 legacy 장소 조회를 시작하지 않고 반환한다.
- 정보모드에서 `/api/map/places`와 TourAPI 직접 호출이 발생하지 않아야 한다.
- 온기 데이터 조회가 이 hook에 계속 필요하다면 장소 조회 effect와 분리해 정보모드의
  장소 요청만 차단한다.

### `src/features/map/components/ListPanel.tsx`

- `mode === 'info'`이면 `InfoPlaceList`를 렌더링한다.
- `mode === 'warmth'`이면 기존 `WarmthFeed`를 유지한다.
- 기존 정보모드 배너와 카드 UI가 필요하면 `InfoPlaceList` 주변 presentation으로 옮기고,
  legacy `PlaceList` 데이터 배열에 다시 결합하지 않는다.

### `src/features/map/components/BottomSheet.tsx`

- 모바일 정보모드에서도 `InfoPlaceList`를 사용한다.
- 데스크톱과 모바일이 서로 다른 목록 source를 사용하지 않게 한다.
- 상세 진입 시 `InfoPlaceItem.placeId`를 canonical ID로 사용한다.

### `src/features/map/components/PlaceMarkers.tsx`

- 정보모드는 `viewportRenderMode === 'PLACE'`일 때만 `viewportItems`의 PLACE item을
  개별 marker로 표시한다.
- REGION, DISTRICT, CLUSTER 단계에서 전국 목록 `listItems`를 marker로 변환하지 않는다.
- 선택·hover 장소의 임시 label 이외에는 줌 단계별 밀도 정책을 따른다.

### `src/features/map/components/CategoryChips.tsx`

- 정보모드에서 legacy `category` 대신 `infoCategory`와 `setInfoCategory`를 사용한다.
- 칩 클릭은 검색창 문구만 바꾸거나 클라이언트 배열을 필터링하지 않고, list와 viewport
  API의 category를 함께 변경한다.
- `한옥` 칩은 BE가 제공할 `HANOK` category를 그대로 전송한다. `SPOT`으로 임시 치환하거나
  응답 후 `isHanok()`으로 거르는 workaround를 추가하지 않는다.

### `src/features/map/components/PlaceList.tsx`

- 정보모드의 전체 건수를 `items.length`로 표시하지 않는다.
- 정보모드에서는 `InfoPlaceList`가 응답의 `totalCount`를 표시하고 cursor page를 append한다.
- `PlaceList`의 10개 단위 client pagination은 legacy/다른 화면 범위로만 제한한다.

### `src/app/api/map/places/route.ts`와 `src/features/map/services/place.service.ts`

- 신규 정보모드의 요청 경로에서 제외한다.
- `category=hanok`을 `null`로 버린 뒤 전체 100건을 반환하는 동작에 의존하지 않는다.
- 장애 시 TourAPI 60건 fixture를 정상 전체 목록처럼 표시하지 않는다.
- 제거 여부는 다른 화면 소비자를 확인한 뒤 결정하되 `/map` 정보모드는 사용하지 않는다.

## 복구해서 사용할 신규 FE 코드

다음 코드는 삭제 대상이 아니라 다시 연결할 구현 자산이다.

| 파일 | 책임 |
| --- | --- |
| `services/infoMap.service.ts` | 정보 목록과 viewport API 요청 조합 |
| `hooks/useInfoMapData.ts` | list·viewport 요청 분리, debounce, abort, servedBbox gate |
| `components/InfoPlaceList.tsx` | `totalCount`, 첫 page, cursor 추가 로딩, region breadcrumb |
| `components/ViewportOverlays.tsx` | REGION·DISTRICT·CLUSTER aggregate와 PLACE 표시 |
| `hooks/useMapStore.ts` | list 상태와 viewport 상태의 독립 관리 |

복구 과정에서 PR #275 이전 코드를 그대로 되돌리는 방식은 피한다. 현재 UI 변경과 검색바,
온기모드 개선을 보존하면서 데이터 경계만 신규 구조로 다시 연결한다.

## 목표 API 계약

아래 계약은 BE 코드에 반영됐다. 현재 운영에는 아직 배포되지 않았으므로 배포 확인 전까지
feature branch 또는 staging에서만 연결한다.

### 전국 목록

```http
GET /api/v1/map/info/places?category=ALL&limit=30
GET /api/v1/map/info/places?category=HANOK&limit=30
GET /api/v1/map/info/places?category=HANOK&limit=30&cursor={nextCursor}
```

FE가 사용하는 필드는 다음과 같다.

```ts
interface InfoPlacePage {
  query: { category: string };
  snapshot: { id: string; publishedAt: string };
  totalCount: number;
  items: InfoPlaceItem[];
  nextCursor: string | null;
  appliedCategories: string[];
  coverage: string;
}
```

- `totalCount`는 조건에 맞는 서버 전체 건수다.
- `items.length`는 현재까지 받은 page 크기이며 전체 건수로 표시하지 않는다.
- `nextCursor`가 있으면 다음 page를 요청해 기존 목록 뒤에 append한다.
- category·region 변경 시 기존 목록과 cursor를 초기화한다.
- 이전 category의 늦은 응답이 현재 목록에 append되지 않도록 request scope를 확인한다.

### 지도 viewport

```http
GET /api/v1/map/info/viewport?bbox={west,south,east,north}&zoomLevel=9&category=HANOK
```

- `renderMode`에 따라 REGION, DISTRICT, CLUSTER, PLACE renderer를 선택한다.
- 응답 `servedBbox` 안의 작은 이동에는 같은 category·snapshot·render bucket 요청을 생략한다.
- category 또는 render bucket이 바뀌면 새 요청을 보낸다.
- 목록 scroll은 viewport 요청을 발생시키지 않는다.
- cluster/region 클릭은 상세를 열지 않고 `bounds` 또는 `targetZoomLevel`로 확대한다.

### 한옥 category 의미

목표 `HANOK` category는 다음 canonical category의 합집합이다.

```text
HANOK
HANOK_STAY
HANOK_CAFE
HANOK_EXPERIENCE
```

FE는 이 네 값을 여러 HTTP 요청으로 fan-out하거나 결과를 직접 deduplicate하지 않는다.
BE가 list, viewport, totalCount, categoryCounts에 같은 의미를 적용한다.

## 로딩과 오류 처리

- 첫 page 로딩: 목록 skeleton을 표시한다.
- 다음 page 로딩: 기존 목록을 유지하고 하단 loading 상태만 표시한다.
- viewport 로딩: 이전 marker·cluster를 유지하고 성공 응답 도착 후 교체한다.
- `400 INVALID_REQUEST`: 요청 category·cursor 계약 오류로 기록하고 자동으로 legacy API로
  fallback하지 않는다.
- `409 SNAPSHOT_EXPIRED`: 현재 category·region의 첫 page부터 다시 조회한다.
- `503 CATALOG_UNAVAILABLE`: 기존 데이터가 있으면 유지하고 재시도 UI를 표시한다.
- `coverage=PARTIAL`: 실패로 간주하지 않되 전체 coverage로 오해하는 문구를 표시하지 않는다.

## 구현 순서

1. BE staging에서 cursor 두 번째 page와 `category=HANOK` 200을 확인한다.
2. `CategoryChips`를 `infoCategory`에 다시 연결한다.
3. `MapPage`에서 `useInfoMapData`와 `ViewportOverlays`를 복구한다.
4. 데스크톱 `ListPanel`과 모바일 `BottomSheet`를 `InfoPlaceList`로 통일한다.
5. 정보모드의 `useMapData` legacy 장소 조회를 차단한다.
6. 현재 배너·카드·검색 UI를 신규 목록 source 위에 다시 배치한다.
7. 단위·통합·브라우저 Network 검증 후 운영에 반영한다.

## FE 필수 테스트

### 데이터 경계

- 정보모드 최초 진입 시 `/api/v1/map/info/places`가 정확히 1회 호출된다.
- 최초 지도 준비 후 `/api/v1/map/info/viewport`가 debounce 뒤 호출된다.
- 정보모드에서 `/api/map/places`와 TourAPI `locationBasedList2`, `searchKeyword2`가
  호출되지 않는다.
- 온기모드 전환이 정보모드 목록 요청을 중복 발생시키지 않는다.

### 목록과 cursor

- 첫 page 30건이어도 헤더는 `totalCount`를 표시한다.
- sentinel 진입 시 다음 cursor page가 append된다.
- 100건을 넘겨도 placeId 중복 없이 계속 로딩된다.
- category 변경 중 이전 요청이 완료돼도 새 목록에 섞이지 않는다.
- `nextCursor=null`이면 추가 요청을 보내지 않는다.

### 한옥 category

- 한옥 칩은 `category=HANOK`을 list와 viewport에 모두 보낸다.
- 한옥 응답에 숙소·카페·체험이 포함돼도 FE가 제거하지 않는다.
- `isHanok()` 이름 판별을 서버 응답 필터로 사용하지 않는다.
- 한옥에서 다른 category로 전환하면 목록, count, viewport가 함께 바뀐다.

### 지도 표현

- level 9 초기 화면은 DISTRICT aggregate를 표시한다.
- 넓은 줌에서 전국 목록 30건을 개별 marker로 표시하지 않는다.
- cluster 클릭 후 확대가 끝난 최종 idle에서 viewport 요청이 1회만 발생한다.
- 작은 pan이 `servedBbox` 안에 있으면 재요청하지 않는다.

## FE 완료 조건

- [ ] 정보모드가 신규 list·viewport API만 사용한다.
- [ ] 화면 전체 수가 `totalCount`와 일치한다.
- [ ] 100건을 초과해 cursor pagination이 이어진다.
- [ ] 한옥 칩이 네 canonical category의 서버 union을 사용한다.
- [ ] 데스크톱과 모바일 목록 source가 같다.
- [ ] REGION·DISTRICT·CLUSTER·PLACE 표현이 줌 단계에 맞다.
- [ ] 기존 배너·카드·검색 UI가 유지된다.
- [ ] loading·abort·stale response·409·503 처리가 검증됐다.
- [ ] 브라우저 Network에서 legacy 지도 API와 TourAPI 직접 호출이 없다.

## BE 준비 완료 확인 기준

FE는 다음 조건이 모두 충족된 BE 환경에서 최종 연동을 검증한다.

- `GET /api/v1/map/info/places?category=HANOK&limit=30`이 200이다.
- 응답 `appliedCategories`가 한옥 관련 네 canonical category를 포함한다.
- 첫 응답의 `nextCursor`로 두 번째 page를 요청하면 200이다.
- 두 page 사이 `placeId`가 중복되지 않는다.
- list `totalCount`와 같은 조건의 viewport count 의미가 문서 계약과 일치한다.
- OpenAPI와 fixture에 `HANOK` category와 cursor 오류 계약이 반영돼 있다.

위 조건 전에는 FE 구현을 운영에 배포하지 않는다. 임시로 legacy API limit을 늘리거나
`HANOK→SPOT` 치환을 추가하면 같은 회귀가 다시 발생한다.
