# Map 정보모드 성능·관측성 운영 정책

## 실행 경계

- 목록과 viewport는 map_place_read_projection만 읽는다.
- 요청 시 원천 TourAPI를 호출하지 않는다.
- DB statement timeout은 기본 1.5초, HTTP API hard timeout 목표는 2초다.
- timeout은 빈 결과로 위장하지 않고 CATALOG_UNAVAILABLE 또는 기존 snapshot fallback으로 관찰 가능하게 처리한다.

## 캐시 키

공개 응답 캐시 키에는 snapshotId, endpoint, category, region-or-bbox, zoom profile, language, schemaVersion을 포함한다.

    map-info:{snapshotId}:{endpoint}:{category}:{regionCode|bbox}:{zoomProfile}:{language}:{schemaVersion}

savedByMe는 공개 캐시에 넣지 않는다. 회원별 저장 상태는 page의 place ID를 한 번에 조회해 별도로 결합한다.

## 측정 게이트

- 목록 첫 페이지 p95 ≤ 200ms
- viewport/aggregate p95 ≤ 500ms
- DB round trip은 목록 count+page 및 viewport 단일 query 경계를 기록한다.
- EXPLAIN (ANALYZE, BUFFERS)에서 projection의 B-tree/GiST 후보 제한을 확인한다.
- 30,000건 synthetic snapshot으로 전국 ALL, 시군구, 작은 bbox, level 4/7/9/12를 각각 측정한다.

## 관측 항목

map.info.query.duration, map.info.query.timeout, map.info.cache.hit,
map.info.cache.miss, map.info.coverage, map.info.pool.exhausted를
endpoint·renderMode·category·snapshot으로 구분하되 장소명과 세션 토큰은 태그로 남기지 않는다.
