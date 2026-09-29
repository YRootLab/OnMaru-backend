# BE 구현 보고서: 지도 정보모드 탐색·목록·클러스터 API

## 1. 목적

상위 설계는 [공동 설계 스펙](../superpowers/specs/2026-09-29-map-info-exploration-design-공통설계스팩.md)이다.

목표는 FE가 category마다 TourAPI를 직접 여러 번 호출하지 않고, BE가 게시된 canonical catalog snapshot을 집계·조회하도록 만드는 것이다.

## 2. 구현 대상 API

### Kakao Map 연동 경계

- Kakao Map JavaScript SDK의 중심·bounds·level은 FE가 관리한다.
- FE가 `map.getBounds()`로 계산한 `minLng,minLat,maxLng,maxLat`를 viewport API가 받는다.
- FE의 Kakao `level`을 `zoomLevel`로 받으며, 숫자가 낮을수록 확대 상태다.
- BE는 Kakao SDK나 Kakao 장소 검색 API를 사용자 요청마다 호출하지 않는다.
- BE는 게시된 WGS84 decimal degree 좌표와 PostGIS로 bbox·region·cluster를 계산한다.
- bbox는 `lng,lat,lng,lat`, 장소 좌표 객체는 `{ lat, lng }` 순서다.
- TourAPI 원천 좌표는 공개 projection 게시 전에 WGS84 범위 검증을 통과해야 한다.

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
servedBbox, renderMode, profileVersion

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

## 5.0 FE 호출 억제를 위한 BE 계약

BE는 FE가 작은 이동을 재사용할 수 있도록 viewport 응답에 실제 조회 범위인 `servedBbox`를 반환한다.

```text
servedBbox = { west, south, east, north }
```

FE는 실제 화면보다 각 방향으로 25% 확장한 bbox를 요청한다. BE는 요청 bbox를 검증·clamp한 뒤, 응답에 최종 조회 범위를 `servedBbox`로 돌려준다.

BE viewport cache key는 다음을 포함한다.

```text
snapshotId + category + regionCode + servedBboxHash + renderBucket + language
```

작은 지도 이동 여부를 BE가 매번 판단할 필요는 없다. FE가 `currentViewport ⊆ servedBbox`인지 판단하고, BE는 동일한 query signature에 대해 빠르게 재사용 가능한 응답을 제공한다.

다만 다음 요청은 반드시 새 query로 처리한다.

- category 변경
- snapshot 변경
- render bucket 변경
- region scope 변경
- 현재 viewport가 servedBbox를 크게 벗어난 경우

BE는 viewport 이동 자체를 history나 cursor 상태로 저장하지 않는다. region scope 진입만 `regionCode`로 처리한다.

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

### FE가 사용할 상세 zoom profile

| Kakao level | BE renderMode | BE 응답 의도 |
|---:|---|---|
| 1~4 | PLACE | 상세 장소 중심 |
| 5 | PLACE | 장소 marker, FE label 최대 40개 |
| 6 | CLUSTER | singleton + 소형 cluster 혼합 |
| 7 | CLUSTER | cluster count 중심 |
| 8~9 | DISTRICT | 읍·면·동/생활권 집계 |
| 10 | DISTRICT | 시군구 집계 |
| 11~12 | REGION | 시도·광역권 집계 |
| 13~14 | REGION | 전국·광역권 요약 |

BE는 장소명 label을 직접 결정하지 않는다. label 최대 수, 충돌 회피, 선택·hover 예외는 FE 책임이다. BE는 FE가 전환을 결정할 수 있도록 `renderMode`, count, bounds, `targetZoomLevel`, `servedBbox`를 제공한다.

BE는 지리·집계 데이터만 제공하고 화면 pixel 배치에는 관여하지 않는다. `center`는 WGS84 지리 좌표, `bounds`는 지역 확대 범위이며, 화면 `x/y`, CSS offset, 사이드바·safe area 보정값은 FE가 계산한다. 행정구역 `count`는 snapshot·category mapping·공개 상태 필터·중복 제거 후 해당 지역 전체 geometry에 포함되는 장소 수이며, `totalCountInViewport`와 구분한다. viewport에 지역 일부만 포함되어도 해당 지역 bubble은 하나만 반환하고 지역 전체 count와 bounds를 사용한다.

cluster/region item의 `bounds`는 FE가 확대 범위를 계산하는 데 사용한다. cluster 클릭 후 FE가 지도 이동을 완료하면 최종 idle에서 한 번만 요청하며, BE는 동일 query key에 대해 idempotent cache 응답을 제공한다.

## 6. persistence·index

현재 catalog projection을 재사용하되 revision, status, category, region, place identity, location, name 조회와 placeId dedupe, region hierarchy lookup, spatial bbox query, active revision filter를 지원해야 한다.

검토 index:

    revision_id, status, category, region_id, place_id
    location GiST index
    revision_id, status, name, place_id

현재 메모리 stream 조회가 3만 건 기준으로 허용되지 않으면 SQL read projection 또는 materialized projection으로 전환한다. Redis와 별도 tile server는 최초 구현의 필수 조건이 아니다.

### 6.1 요청 경로 SQL 실행 원칙

정보모드 요청에서 Java가 장소 3만 건을 모두 읽은 뒤 Stream/filter/sort/cluster를 수행해서는 안 된다. DB가 필터·정렬·집계·limit을 수행하고 Java는 제한된 projection DTO만 받아야 한다.

- Spring API는 read-only transaction과 JDBC/jOOQ 등 명시적 projection query를 사용한다. JPA entity 전체 hydrate와 요청 중 aggregate 복원을 금지한다.
- raw TourAPI 원장과 사용자 요청 시 외부 API 호출을 금지한다.
- FE category 확장은 게시 시 검증·고정된 mapping registry/read projection으로 처리한다.
- 장소별 이미지·상세 조회를 반복하지 않는다. 공개 projection 또는 batch query만 사용한다.
- `totalCount`는 전체 item을 Java로 읽어 계산하지 않는다. publication 시 만든 scope/category count projection 또는 DB count query를 사용한다.
- 목록 query는 keyset cursor와 `LIMIT limit+1`을 사용한다. offset pagination과 애플리케이션 정렬을 사용하지 않는다.

권장 read model:

```text
map_place_read_projection
  revision_id, place_id, public_id, name, normalized_name,
  status, location_geom, region codes, display_category,
  thumbnail_url, summary, sort_key

map_place_category_projection
  revision_id, place_id, canonical_category

map_scope_count_projection
  revision_id, scope_type, region_code, canonical_category, place_count
```

권장 index/query 경로:

- 목록: `(revision_id, canonical_category, region_code, sort_key, place_id)` keyset index
- 전체 목록: `(revision_id, sort_key, place_id)` keyset index
- bbox: `location_geom` GiST + revision/status/category 조건
- DISTRICT/REGION: `map_scope_count_projection`에서 지역 count 조회
- CLUSTER: bbox로 후보를 먼저 제한한 후 deterministic grid `GROUP BY`

projection migration에서 최소한 다음 index 계약을 검토한다.

```sql
CREATE UNIQUE INDEX ... ON map_place_read_projection (revision_id, place_id);
CREATE INDEX ... ON map_place_read_projection (revision_id, region_code, sort_key, place_id) WHERE status = 'ACTIVE';
CREATE INDEX ... ON map_place_read_projection (revision_id, sort_key, place_id) WHERE status = 'ACTIVE';
CREATE INDEX ... ON map_place_read_projection USING GIST (location_geom);
CREATE UNIQUE INDEX ... ON map_place_category_projection (revision_id, place_id, canonical_category);
CREATE INDEX ... ON map_place_category_projection (revision_id, canonical_category, place_id);
CREATE UNIQUE INDEX ... ON map_scope_count_projection (revision_id, scope_type, region_code, canonical_category);
```

기존 원천 `geography(Point,4326)`를 요청마다 geometry로 cast하지 않도록 map projection에는 `geometry(Point,4326)`인 `location_geom`을 게시하거나 동등한 expression index를 둔다. 정보모드 bbox·region query는 geometry 연산, 거리 정렬은 geography 연산으로 분리한다.

publication 시 projection을 원자적으로 교체하고 row count/checksum을 검증한다. 불완전한 revision은 public query에서 보이지 않아야 한다.

## 7. 오류·cache·관측성

구현 시 공개 projection 조회 port와 viewport store 앞에 snapshot-aware bounded in-memory cache를 둔다. 기본 TTL은 30초, 최대 엔트리는 256개이며 `MapInfoQuery`/`MapInfoViewportQuery` 전체가 cache key가 된다. snapshotId·category·region/bbox·zoom·language가 key에 포함되므로 publication revision이 바뀌면 이전 응답과 충돌하지 않는다. `savedByMe`는 이 public read cache에 저장하지 않고 조회 후 결합한다. Redis는 이후 다중 인스턴스 hit율 요구가 확인될 때 교체 가능한 운영 선택지다.

기존 `/api/v1/map/places`, `/api/map/places`는 production에서 `MapInfoLegacyQueryAdapter`를 통해 동일한 projection SQL 경로를 사용하고 기존 응답 DTO로 변환한다. 따라서 FE #246 전환 기간에도 기존 호출이 원천 catalog 전체를 JVM으로 적재해 필터링하지 않는다. 기존 응답의 `linkedOdiiStoryIds`와 관측 availability는 신규 map projection에 없는 경우 빈 값/`MISSING`으로 유지하며, 신규 정보모드 API의 상세 계약은 `/api/v1/map/info/places`를 사용한다.

- invalid category/bbox/zoom/limit: 400 INVALID_REQUEST
- cursor snapshot 불일치: 409 SNAPSHOT_EXPIRED
- active snapshot 없음: 503 CATALOG_UNAVAILABLE
- 세션 없음: public 조회 성공, savedByMe=false
- image/detail 누락은 장소 API 실패가 아니다.
- mapping 일부 미게시: coverage=PARTIAL과 appliedCategories 반환
- stale cache 사용 시 stale=true와 asOf 반환
- TourAPI 장애는 게시 snapshot 조회에 영향을 주지 않는다.

cache key에는 snapshotId, category, regionCode 또는 bboxHash, zoomLevel/profileVersion, language를 포함한다. savedByMe 결과는 public cache와 분리한다.

### 7.1 SQL 성능·실행계획 검증

- 목록·viewport·count·cluster query에 `EXPLAIN (ANALYZE, BUFFERS)`를 적용하고 결과를 fixture/검증 기록으로 남긴다.
- 3만 건 snapshot에서 상세 bbox, 시군구 bbox, 전국 `ALL` 요청을 별도로 측정한다.
- 초기 목표는 목록 첫 page p95 200ms 이하, viewport/aggregate p95 500ms 이하, API hard timeout 2초 이하로 둔다.
- DB statement timeout은 1.5초로 API hard timeout보다 짧게 설정한다.
- 핵심 요청의 SQL round trip은 1~3회 이내, 장소별 반복 query(N+1)는 0건이어야 한다.
- 전국 ALL query에서 planner가 Seq Scan을 선택하더라도 실제 p95·buffer read가 예산 안이면 무조건 index scan을 강제하지 않는다.
- connection pool 고갈, slow query, statement timeout, cache hit/miss, projection refresh 실패를 관측한다.

로그 필드:

    requestId, snapshotId, category, appliedCategories
    regionCode, zoomLevel, renderMode, bboxHash
    limit, itemCount, totalCount, queryDurationMs
    cacheHit, coverage, stale, cursorHash

Micrometer metric:

    onmaru.map.info.requests{endpoint,outcome,category}
    onmaru.map.info.query.duration{endpoint,outcome,category}
    onmaru.map.info.cache.hit{endpoint}
    onmaru.map.info.cache.miss{endpoint}
    onmaru.map.info.cache.entries

목록과 viewport controller는 성공·invalid·unavailable outcome과 조회 시간을 기록하고, 각 public read cache는 hit/miss/entry 수를 기록한다. 장소명·회원 세션·원천 payload는 metric tag나 로그에 넣지 않는다.

## 8. 테스트 요구사항

### 8.0 W0 계약 산출물

Wave 0의 기계 판독 계약은 다음 파일을 기준으로 한다.

- `docs/contracts/openapi/map-info.openapi.yaml`
- `docs/contracts/fixtures/map-info/`
- `modules/catalog/src/main/java/com/yrootlab/onmaru/catalog/application/query/mapinfo/`
- `docs/database/modules/catalog.dbml`의 `map_*_projection` 및 `map_projection_publications`

이 단계는 API schema/DTO/fixture와 논리 projection metadata만 동결한다. 실제 Spring controller wiring, keyset SQL, PostGIS cluster/region query, publication refresh는 #495~#497에서 구현한다.

Contract fixture:

- list normal, empty, next page, snapshot expired
- ALL response와 SPOT expanded category response
- viewport REGION, DISTRICT, CLUSTER, PLACE
- viewport servedBbox와 render profile
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
- 동일 servedBbox 재요청 cache hit와 작은 pan 재사용 시나리오

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
