# BE 구현 보고서: 지도 정보모드 탐색·목록·클러스터 API

## 1. 목적

상위 설계는 [공동 설계 스펙](../superpowers/specs/2026-09-29-map-info-exploration-design.md)이다.

목표는 FE가 category마다 TourAPI를 직접 여러 번 호출하지 않고, BE가 게시된 canonical catalog snapshot을 집계·조회하도록 만드는 것이다.

## 2. 구현 대상 API

### 목록 API

    GET /api/v1/map/info/places

지원 query:

- category: ALL, SPOT, EXPERIENCE, CULTURE, FESTIVAL, STAY, FOOD, CAFE, MARKET
- regionCode, bbox, cursor, snapshotId, language
- limit: 기본 30, 최대 100
- sort: REGION_NAME, NAME, DISTANCE
- lat/lng: distance sort에서 필수

응답 필수 필드:

    schemaVersion, query, snapshot, totalCount, items
    nextCursor, appliedCategories, coverage

장소 item 필수 필드:

    placeId, name, displayCategory, matchedCategories
    region, coordinates, thumbnailUrl, summary
    savedByMe, dataAvailability

### 지도 viewport API

    GET /api/v1/map/info/viewport

지원 query:

- bbox=minLng,minLat,maxLng,maxLat
- zoomLevel, category, regionCode, snapshotId, language
- limit: 기본 500, 최대 1000

응답 필수 필드:

    schemaVersion, renderMode, profileVersion, snapshot
    totalCountInViewport, items, appliedCategories, coverage

item type:

- REGION: 시도·광역권 count
- DISTRICT: 시군구·읍면동 count
- CLUSTER: grid cell count
- PLACE: 장소 marker

## 3. category mapping

| FE category | canonical 조회 범위 |
|---|---|
| SPOT | HANOK, HISTORIC_SITE, 검수된 CULTURE_ART, 한옥마을·고택 |
| EXPERIENCE | HANOK_EXPERIENCE, 검수된 LOCAL_SCENE |
| CULTURE | HISTORIC_SITE, CULTURE_ART, 승인된 서원·향교·사찰 |
| FESTIVAL | 별도 EVENT projection |
| STAY | HANOK_STAY, 승인된 전통 숙박 |
| FOOD | TRADITIONAL_FOOD, 승인된 한식·향토음식·노포 |
| CAFE | HANOK_CAFE, 전통찻집, 승인된 카페 |
| MARKET | TRADITIONAL_MARKET, 장터, 승인된 시장 인접 상점 |
| ALL | 공개 가능한 모든 canonical place |

정책:

- 제목 키워드 기반 자동 분류 금지
- TourAPI 원천 분류 코드와 승인된 registry 사용
- mapping version을 dataset revision metadata에 기록
- 여러 mapping에 걸친 장소는 placeId 기준 1회만 반환
- appliedCategories와 matchedCategories를 응답에 포함
- FESTIVAL은 일반 place count에 섞지 않음

## 4. 목록 query 구현

- active published catalog revision만 조회한다.
- raw TourAPI 원장을 web request에서 직접 조회하지 않는다.
- snapshotId를 cursor와 함께 고정해 page 간 결과가 흔들리지 않게 한다.
- snapshot 변경 후 이전 cursor는 409 SNAPSHOT_EXPIRED로 처리한다.
- offset이 아닌 keyset cursor를 사용한다.
- cursor에는 snapshot, category, mapping version, sort key, 마지막 place key를 opaque/signature 형태로 포함한다.
- totalCount는 mapping·dedupe·공개 상태 필터 후 계산한다.
- regionCode와 bbox가 함께 오면 교집합을 적용한다.
- 공개 불가, quarantine, 삭제 상태, 좌표 결측 장소는 기본 제외한다.
- category가 없으면 SPOT을 기본값으로 한다.

## 5. viewport·cluster query

초기 Kakao level 기준:

| Kakao level | renderMode |
|---:|---|
| 1~5 | PLACE |
| 6~7 | CLUSTER |
| 8~10 | DISTRICT |
| 11 이상 | REGION |

응답에 profileVersion=map-zoom-v1을 포함한다.

집계 규칙:

- REGION/DISTRICT는 canonical region table과 행정구역 경계를 사용한다.
- CLUSTER는 zoom별 deterministic grid cell을 사용한다.
- cluster에는 clusterId, center, bounds, count, categoryCounts를 포함한다.
- cluster ID는 snapshot·zoom·grid cell에 대해 deterministic해야 한다.
- PLACE limit을 초과하면 임의의 일부 장소를 반환하지 말고 cluster 응답으로 전환한다.
- 단순 위경도 반올림으로 시군구를 추정하지 않는다.

## 6. persistence·index

현재 catalog projection을 재사용하되 revision, status, category, region, place identity, location, name 조회와 placeId dedupe, region hierarchy lookup, spatial bbox query, active revision filter를 지원해야 한다.

검토 index:

    revision_id, status, category, region_id, place_id
    location GiST index
    revision_id, status, name, place_id

현재 메모리 stream 조회가 3만 건 기준으로 허용되지 않으면 SQL read projection 또는 materialized projection으로 전환한다. Redis와 별도 tile server는 최초 구현의 필수 조건이 아니다.

## 7. 오류·cache·관측성

- invalid category/bbox/zoom/limit: 400 INVALID_REQUEST
- cursor snapshot 불일치: 409 SNAPSHOT_EXPIRED
- active snapshot 없음: 503 CATALOG_UNAVAILABLE
- 세션 없음: public 조회 성공, savedByMe=false
- image/detail 누락은 장소 API 실패가 아니다.
- mapping 일부 미게시: coverage=PARTIAL과 appliedCategories 반환
- stale cache 사용 시 stale=true와 asOf 반환
- TourAPI 장애는 게시 snapshot 조회에 영향을 주지 않는다.

cache key에는 snapshotId, category, regionCode 또는 bboxHash, zoomLevel/profileVersion, language를 포함한다. savedByMe 결과는 public cache와 분리한다.

로그 필드:

    requestId, snapshotId, category, appliedCategories
    regionCode, zoomLevel, renderMode, bboxHash
    limit, itemCount, totalCount, queryDurationMs
    cacheHit, coverage, stale, cursorHash

## 8. 테스트 요구사항

Contract fixture:

- list normal, empty, next page, snapshot expired
- ALL response와 SPOT expanded category response
- viewport REGION, DISTRICT, CLUSTER, PLACE
- partial/stale response
- invalid bbox/category/zoom/limit

Integration:

- category mapping과 동일 place dedupe
- totalCount와 실제 page 순회 결과 일치
- cursor 순회 중 duplicate/omission 없음
- snapshot 교체 시 cursor conflict
- bbox·regionCode 교집합
- cluster count와 공개 장소 수 일치
- quarantine·좌표 결측 제외
- festival이 place count에 혼입되지 않음
- 3만 건 snapshot 전국·시도·시군구·상세 bbox 성능

## 9. 구현 순서와 완료 조건

1. category mapping registry와 version metadata 확정
2. OpenAPI DTO·fixture 작성
3. list query service·cursor 구현
4. list controller와 integration test 구현
5. active snapshot SQL/index 또는 read projection 구현
6. viewport render mode 구현
7. district/region/cluster aggregate 구현
8. cache·관측성·partial response 구현
9. FE contract test와 실제 FE 연동
10. legacy /map/places fallback deprecation 및 제거 계획 수립

완료 조건:

- FE 정보모드가 TourAPI 직접 호출 없이 두 BE API만 사용한다.
- 기본 SPOT이 고택 관련 canonical category로 확장된다.
- ALL 목록이 cursor와 정확한 totalCount를 제공한다.
- ALL viewport가 줌에 따라 aggregate/cluster/place로 전환된다.
- 동일 place 중복이 없다.
- active snapshot과 cursor가 일관된다.
- TourAPI 장애가 사용자 조회 요청으로 전파되지 않는다.
- OpenAPI, fixture, contract test, integration test가 통과한다.

