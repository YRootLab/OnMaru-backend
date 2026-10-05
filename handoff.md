# handoff.md

## 2026-10-05 Issue #638 CD Gradle profile 적용

- 브랜치: `perf/638-cd-gradle-profile`; 기준: `origin/develop` commit `43b2a74`.
- 요청: Issue #525에서 채택한 workers 2 / Gradle build cache disabled profile을 Spring Docker builder와 deploy/release migration rehearsal에도 opt-in으로 적용한다.
- cache 경계: `actions/setup-java` dependency/wrapper cache와 Docker BuildKit cache mount·GHA layer cache는 유지한다. 끄는 것은 cold 실험에서 별도 이득이 확인되지 않은 Gradle task-output build cache다.
- 안전 조건: Docker `bootJar -x test`, Migration test filter, image digest, staging/readiness/rollback gate는 바꾸지 않는다. 먼저 계약 테스트가 profile 누락으로 실패하는 것을 확인한 뒤 최소 설정을 반영한다.
- TDD: 새 deploy/release 계약 2개가 Dockerfile과 두 migration gate의 init script 누락으로 실패하는 것을 확인한 뒤, 세 Gradle 명령에 opt-in profile을 추가해 17/17 통과시켰다. 외부 dependency·BuildKit cache와 기존 task/filter는 테스트로 보존한다.
- 검증: 전체 Node 227/227, `scripts/verify-contracts`, `git diff --check`가 통과했다. 실제 `docker build --target builder`에서 새 profile을 소비한 `bootJar -x test`가 45초에 성공했다. 로컬에 `actionlint`가 없어 해당 별도 lint는 실행하지 못했으며 workflow 구조는 repository contract tests와 GitHub PR CI에서 재검증한다.
- 관련: [#638](https://github.com/YRootLab/OnMaru-backend/issues/638), #525, PR #633.

## 2026-10-05 Issues #525/#556 CI 3+3 실측 완료

- 브랜치: `feature/525-ci-performance-measurement`; baseline `a91698f`, candidate `b7f1d0d`, controller [37215998513](https://github.com/YRootLab/OnMaru-backend/actions/runs/37215998513).
- 여섯 run attempt 1이 모두 성공했고 API/artifact identity, 공통 source tree·test plan, final provenance attestation이 검증됐다. Exclusion과 실패는 없다.
- baseline 4 workers/cache `[495, 711, 712]`초, 중앙값 711초·범위 217초. candidate 2 workers/no-cache `[751, 545, 541]`초, 중앙값 545초·범위 210초.
- Toolkit 결과는 `comparable`, `no_regression`, relative delta `-0.23347398030942335`; candidate가 중앙값 기준 약 23.35% 짧다. 표본 3회와 큰 범위 때문에 통계적 유의성은 주장하지 않는다.
- 결정: candidate config를 shared Java CI에 반영하되 required test scope는 유지한다. 병합 후 실제 `CI / verify` 추세가 악화되면 같은 절차로 재측정한다.
- 다음 단계: 보고서·README·config PR 검증/병합, #525/#556와 Toolkit #122/#135 증적 연결 및 종료. 별도로 #543 release tag 3+3 run은 진행 중이다.

## 2026-10-05 Issue #555 Cloud 왕복 완료 및 #556 실험 준비

- 브랜치: `docs/555-cloud-observability-evidence`; 기준: release `v0.3.39`의 master→develop 역병합 PR #631 merge commit `6af4d0b`.
- release: PR #629 병합, tag/GitHub Release `v0.3.39` (`86a09b3`), master CI와 image/deploy workflow 성공, PR #631 역병합 완료.
- Cloud: 성공 source `37212332117`/observer `37212822953`, 실패 source `37214663596`/observer `37215389263` 모두 metrics+traces 2/2 ACK. Tempo 성공 trace `be40e6de233f9411e66f3bc38718f4a`, 실패 trace `f1248afad9e65b7f91118d0e7d228cf8`에서 run ID·result·manifest digest·Toolkit pin 일치를 확인했다.
- 사용량: Grafana Metrics Drilldown CI/trace metric 16종, Stack Home active series 29·4 DPM·24시간 46 spans·1 service. 현재 trial 14일 잔여이며 공개 Free 한도는 10k active series, traces 50 GB/month, 14일 보존, $0이다.
- local outage: 8,192 synthetic spans, export failure peak 512, queue pressure 6.25, in-flight 10을 관측하고 Tempo 복구·dashboard smoke·duplicate replay 억제를 확인했다.
- 다음 단계: 이 문서 PR 검증·병합과 #555 종료 후 `feature/525-ci-performance-measurement`를 최신 develop에 병합하고, Toolkit bootstrap gate로 #556 실제 ci 3+3 dispatch를 정확히 1회 실행한다.

## 2026-10-05 Issue #625 v0.3.39 CI 통합 운영 승격

- 브랜치: `release/v0.3.39`, 기준: `origin/develop` merge commit `db2474e` (PR #624).
- 목적: Grafana Cloud 호환 Toolkit pin과 제한된 #556 bootstrap gate를 default branch `master`에 승격해 #555 정식 replay와 실제 3+3 benchmark를 실행 가능하게 한다.
- 절차: release PR의 필수 CI 통과 → `master` merge → Release Please/tag 확인 → `develop` 역동기화 → 새 default-branch workflow로 live integration 검증.
- 관련: #543, #554, #555, #556, #568, #625. 실제 ACK·query·attestation 증적 전에는 통합 이슈를 종료하지 않는다.
## 2026-10-05 운영 정보지도 표시·줌 평가

- 사용자 제보: `onmaru.site` 정보모드에서 줌아웃하면 `30:200` 등 내부 지역 코드가 표시되고 집계 마커가 늦게 갱신됨. 스크린샷 4장 제공.
- 운영 `GET https://api.onmaru.site/api/v1/map/info/viewport` 재현: level 8 응답 `name=regionCode=30:200`, `count=154`; level 9 응답도 `name=regionCode`가 숫자 코드임. HTTP 200으로 집계 자체는 반환됨.
- BE 원인 경로: `JdbcTourApiCatalogPublisher`가 지역 매칭 실패 시 raw 코드를 `sido_code` 및 `sigungu_code=concat_ws(':', ...)`로 저장하고, `JdbcMapViewportQueryRepository.regions`가 `catalog_regions` 이름 조회 실패 시 `coalesce(max(region.name), candidates.region_code)`로 코드명을 반환함. `catalog_regions`의 내부 코드는 `kr-*` 형태여서 raw 코드와 불일치할 수 있음. 운영 DB의 실제 매핑 누락 상태는 미확인.
- FE 표시·지연 경로: `ViewportOverlays`가 응답 `item.name`과 `count`를 그대로 표시하고 200 이상은 `200+`로 절단. `viewportRefreshPolicy`는 idle 후 900ms, 2단계 이상 줌 변화에만 commit; `useInfoMapData`가 추가로 700ms debounce 후 요청. 응답 전 이전 마커를 유지하므로 1단계 줌 시 오래된 집계가 보일 수 있음.
- 2026-10-05 후속 구현: BE Issue #630, 브랜치 `fix/630-map-region-names`. V041에 V032 검증 목록의 285개 한국어 행정구역 표시명을 활성 dataset 시점과 독립된 조회표로 추가하고, viewport 조회에서 raw `30:200`·`11:110`을 `유성구`·`종로구`로 표시한다. `regionCode`는 목록 필터 계약을 위해 유지하며 미매핑 이름은 `이 지역`으로 반환한다. PostgreSQL 통합 회귀에서 실패→통과를 확인했다. 집계 후 이름 조회로 쿼리를 바꾼 뒤 publisher 전체 24건(3만 장소 성능 기준 포함), 계약 검증, migration registry 정책이 통과했다. FE Issue #325는 별도 worktree `fix/325-map-zoom`에서 지도 테스트 149건·TypeScript·ESLint·production build를 통과하고 원격 브랜치까지 게시했다. 운영 배포는 미수행.

## 2026-10-04 Issues #543/#554/#555/#556 실 통합 검증

- 브랜치: `fix/543-556-integration` (`origin/develop` 기준), 관련 이슈: #543, #554, #555, #556 및 Toolkit #122/#115.
- 사용자 요청: Grafana Cloud 실 전송, 수동 pipeline 3+3, release 3회 비교까지 검증하고 README에 구체적인 실행·벤치마킹 절차를 기록한 뒤 완료 조건을 충족한 관련 이슈를 닫는다.
- 외부 설정: Grafana Cloud Stack을 생성했고 GitHub `ci-observability` environment에 `OTLP_ENDPOINT`, `OTLP_HEADERS`, 회전용 `GRAFANA_OTLP_TOKEN` secret 이름이 등록됐다. secret 값은 문서·로그·artifact에 기록하지 않는다.
- 설계: `docs/superpowers/specs/2026-10-04-ci-benchmark-live-integration-design.md`. production 배포와 과거 release 비교 검증을 분리하고, 실제 Actions/Grafana 증거가 있는 이슈만 종료한다.
- 다음 단계: 설계 검토 후 TDD로 관측 미설정 처리·release evidence 수집/검증 경로를 보강하고 README를 갱신한다. 이후 PR/merge, live replay, Grafana query, 3+3 experiment, release 3+3 순으로 실행한다.
- 위험: Cloud token은 만료 전에 교체해야 한다. 과거 release asset에는 현재 `release-module-evidence.json`이 없어 실제 tag별 3회 수집이 필요하다.

## 2026-10-04 Issue #604 백엔드 viewport 집계·응답 계약 보강 (운영 배포 제외)

- 추가 수정: category projection 행이 누락된 상태에서 `HANOK_CAFE` 장소가 있어도 `category=CAFE`가 0건이 되는 count/item 필터 불일치를 PostgreSQL 통합 테스트로 재현했다. `queryValues`가 `appliedCategories`와 같은 원본·canonical 분류 집합을 사용하도록 수정해 개별 탭도 `ALL`/`HANOK`과 동일한 보장으로 조회한다. 전체 `:modules:catalog:test :apps:spring-api:test`(12분), 3만 건 성능 테스트(1분 8초), `:apps:spring-api:bootJar`, 계약 문서 검증이 통과했다. 운영의 서울 bbox level 8~12는 현재도 `1569/0`이며 운영 SHA는 공개 응답에서 `x-revision: unknown`으로 확인 불가하다.
- 사용자 결정: 프론트 지도 수정은 별도로 master 배포했고, 이 세션에서는 백엔드 코드 오류만 개선한다. 운영 배포와 운영 smoke는 사용자가 직접 수행한다.
- 브랜치 `hotfix/604-map-viewport-ux`의 미커밋 변경: category projection 행 누락 시에도 장소의 display category로 CLUSTER/DISTRICT/REGION을 집계하고, viewport 전체 count를 표시 item 제한과 분리한다. 지역 코드 누락 등으로 행정구역 집계가 비면 좌표 기반 CLUSTER로 전환해 장소를 유지한다. 캐시도 양수 count·빈 집계를 저장하지 않는다.
- FE 계약을 위해 최상위 nullable `snapshotId`를 명시적으로 직렬화하고, viewport 시각 요소를 기본·최대 60개로 제한한다. 일부만 겹치는 bbox는 한국 지원 범위(120–132°E, 30–45°N)와 교집합 조회하고 완전 영역 밖은 `INVALID_REQUEST`로 반환한다. timeout/503은 줌·bbox·category·duration·itemCount를 key-value 로그로 남긴다.
- TDD: category projection 누락, 제한된 item과 전체 count·PARTIAL coverage, 지역 코드 누락, timeout 로그, snapshotId, bbox 경계, limit=0 테스트에서 수정 전 실패를 확인했다. 수정 후 집중 회귀와 3만 건 성능 테스트가 통과했다. 최종 `./gradlew :modules:catalog:test :apps:spring-api:test`는 12분 26초에 성공했고, `:apps:spring-api:bootJar`, `scripts/verify-contracts --contracts-only`, `git diff --check`, branch parser(#604)도 통과했다.
- 운영 실제 DB의 category/지역 매핑 상태와 컨테이너 SHA는 확인되지 않았다. 공개 API는 여전히 level 8–12에서 양수 total·빈 items를 반환한다. 배포 후 사용자가 운영 SHA 및 level 5–12 ALL/HANOK 실응답을 확인해야 #604를 완료할 수 있다.
- 2026-10-04 추가 진단·수정: 운영 서울 bbox `126.9,37.5,127.1,37.7`에서 level 5~7은 1569/60, level 8~12는 1569/0을 재현했다(전체/item 수). `LIMIT 60`이 장소·cluster·district를 조용히 누락시키는 문제를 재현한 후, 밀집 PLACE→CLUSTER, 초과 DISTRICT→REGION→CLUSTER의 순서로 전체 장소를 대표하도록 수정했다. Kakao의 작은 level이 확대라는 규칙에 맞춰 집계 클릭의 `targetZoomLevel`을 현재보다 작게 바꿨다. 다중 category 매핑의 지역 count는 place ID 중복 제거로 계산한다. PostgreSQL/PostGIS 통합 테스트 18건과 `git diff --check`가 통과했다. 운영 DB는 확인되지 않았고 코드는 미커밋·미배포 상태다.
- 추가 회귀: 같은 bbox에 지역 코드가 있는 장소와 없는 장소가 섞이면 기존 집계는 일부 장소만 표시하면서 `coverage=PARTIAL`이 되는 것을 재현했다. 지역 집계 item count 합계가 `totalCountInViewport`와 다르면 좌표 기반 CLUSTER로 전환하고, 최종 count 불일치는 200으로 숨기지 않고 실패 처리한다. 카테고리별 그룹의 첫 좌표를 지역 전체 중심으로 쓰던 오류와 복수 카테고리 장소가 cluster 중심에 중복 가중되던 오류를 고유 장소 기준 좌표 집계로 수정했다. 각 문제는 실패 테스트→수정→통과로 확인했다.
- 최신 검증: `./gradlew :modules:catalog:test :apps:spring-api:test --console=plain` 성공(11m55s), 3만 장소 성능 테스트 단독 성공(1m14s), `bash scripts/verify-contracts --contracts-only` 성공, `./gradlew :apps:spring-api:bootJar --console=plain` 성공. 운영 반영은 하지 않았다.

## 2026-10-04 ADR-0016 운영 배포 시간·수동 호출 정책

- 사용자 재확인: 검증된 `master`의 정기 운영 배포는 KST 03:00, 낮 시간 즉시 배포는 `onmaru-production-deploy`의 명시적 호출에 한정한다. `master` push의 build 성공은 운영 배포 성공이 아니다.
- 이 결정을 `docs/decisions/0016-production-deployment-window.md`에 `proposed`/retrospective ADR로 기록했다. 현재 workflow에는 예약 트리거와 수동 운영 배포 job이 없고 GitHub `production` environment도 없다는 구현 간극을 별도로 명시했다.
- ADR Toolkit significance 12점(`recommended`), 전체 ADR validate 16건·오류 0건, index 성공.

## 2026-10-04 프로젝트 운영 배포 스킬 위치 정리

- 사용자 요청에 따라 개인 경로의 `onmaru-production-deploy`를 이 저장소의 `skills/onmaru-production-deploy/`에 동일한 내용으로 추가했다. `SKILL.md`와 `agents/openai.yaml`이 원본과 byte-for-byte 일치함을 확인했다.
- 저장소의 `skills/`를 프로젝트 정본으로 사용한다. 개인 경로의 원본은 다른 프로젝트에서의 사용 가능성을 보존하기 위해 삭제하지 않았다.

## 2026-10-04 Issue #604 운영 재점검 (배포 미완료)

- 현재 작업 브랜치: `hotfix/604-map-viewport-ux` (`origin/develop`의 #606 병합 SHA `c93454d`에서 시작). API 기본 limit과 요청 limit 상한을 60으로 낮추고 controller 회귀 테스트를 추가했다. 테스트는 기존 구현에서 실패한 뒤 수정 후 통과했다.
- 원격 `master`는 #605 병합 SHA `74e3143`, `develop`은 #606 병합 SHA `c93454d`. `master` push의 Deploy run 37144742725는 이미지 build/scan과 migration gate만 통과했고 운영 배포 job이 없다. `infra/lightsail/staging/README.md`도 master push가 운영 Lightsail을 배포하지 않는다고 명시한다.
- 2026-10-04 공개 운영 서울 bbox `126.9,37.5,127.1,37.7`, `category=ALL`, `limit=60`: level 5 PLACE 1569/60, 6~7 CLUSTER 1569/60, 8~10 DISTRICT 1569/0, 11~12 REGION 1569/0 (표기: total/items). 최신 #605 구현은 집계 total을 items count 합계로 계산하므로 현재 운영 응답은 해당 구현과 불일치한다. 전국 bbox 8개 병렬 요청은 모두 503으로 끝났다.
- 운영 이미지/컨테이너의 정확한 SHA는 공개 read-only endpoint로 확인되지 않았다. 운영 DB 행정경계 row 누락·매핑 불일치는 이전 SQL의 필수 JOIN과 현상에 합치하지만 DB 직접 증거는 없다. 기본 캐시 TTL 30초·stale-if-error 30초이므로 장기 지속 현상을 캐시만으로 설명하기 어렵다.
- 운영 배포 진입점 `deploy_production`/`production-deploy`와 repository variable `PRODUCTION_DEPLOY_ENABLED`가 없어 `onmaru-production-deploy` 절차는 사전 확인에서 중단됐다. 다음 단계는 Git Flow로 운영 배포 진입점 마련 또는 검증된 운영 배포 수단 확인, `74e3143` 이미지/컨테이너 배포 SHA 확인, level 5~12 및 ALL/HANOK smoke, 필요 시 운영 DB read-only 점검이다. Issue #604는 운영 AC를 확인할 때까지 열어 둔다.
## 2026-10-04 Issue #617 카카오 탈퇴 후 신규 회원 재가입

- 브랜치: `fix/617-kakao-rejoin`.
- 사용자 결정: 탈퇴 후 같은 카카오 계정으로 다시 로그인하면 이전 회원을 복구하지 않고 새 회원 ID와 기본 프로필을 발급한다.
- 원인: DELETING 회원의 외부 계정 연결이 남아 OAuth 로그인에서 이전 회원 ID를 반환하고 세션 생성이 거부됐다.
- 변경: JDBC와 InMemory identity store가 DELETING 연결을 재로그인 transaction에서 해제하고 새 ACTIVE 회원에 연결한다. 기존 회원의 유효한 관리자 제재는 연결 해제 전에 검사해 우회를 막는다. 이전 회원의 deletion ledger·데이터 정리는 독립적으로 계속한다.
- 검증: OAuth 로그인 단위 테스트와 JDBC/PostgreSQL 통합 테스트 통과. Node hygiene 225개, planning input/secret scan, Odii fixture 8개, `git diff --check`, branch parser #617 통과. 실제 운영 계정 탈퇴·재가입 smoke와 배포는 아직 수행하지 않았다.
- 다음 단계: CI verify와 코드 리뷰 후 PR을 통해 develop에 병합하고 운영 반영 뒤 재가입 smoke를 확인한다.

## 2026-10-04 Issue #604 지도 줌아웃 집계 빈 응답·timeout hotfix

- 브랜치: `hotfix/604-map-viewport-aggregates` (`origin/develop` 기준).
- 운영 재현: 전국·서울 bbox의 `DISTRICT/REGION` 응답이 양수 `totalCountInViewport`와 빈 `items`를 함께 반환했고, 같은 전국 요청에서 간헐적으로 `503 CATALOG_UNAVAILABLE`·`details.timeout=true`가 발생했다.
- 원인: 원거리 집계가 장소 projection과 별도로 적재되는 행정경계 row를 필수 JOIN해, 운영에서 경계가 누락되거나 행정코드와 불일치하면 집계가 모두 사라졌다. 또한 집계 전에 동일 viewport 전체 count 공간 쿼리를 별도로 수행해 timeout 비용을 더했다.
- 변경: DISTRICT/REGION은 `map_place_read_projection`의 `sido_code`/`sigungu_code`와 point geometry를 직접 그룹화해 count·center·bounds를 반환한다. 지역명은 `catalog_regions`가 있으면 사용하고 없으면 코드로 안전하게 대체한다. 원거리 `totalCountInViewport`는 반환 집계의 합계로 계산해 중복 공간 count 쿼리를 제거한다. Frontend Issue #246의 level 6 혼합 계약에 맞춰 단일 장소 grid cell은 `PLACE` item, 2개 이상은 `CLUSTER` item으로 반환한다.
- 회귀: 행정경계 row 없이 ALL·HANOK의 DISTRICT/REGION 집계와 total count가 반환되는 PostgreSQL/PostGIS 통합 테스트를 추가했다.
- 다음 단계: 전체 관련 회귀·성능 테스트와 CI `verify`를 통과한 뒤 `master` hotfix 배포, 운영 전국/서울 viewport smoke로 non-empty aggregate와 timeout 부재를 확인하고 `develop`에 forward-port한다.

## 2026-10-03 Issue #552 회원 익명 프로필·온기 후기 작성자

- 브랜치: `feature/552-member-profile` (branch parser #552).
- 사용자 결정: 카카오 닉네임·프로필 이미지는 사용하지 않는다. 가입 시 익명 이름과 `CHARACTER_01~10`, `BACKGROUND_01~10` 조합을 자동 배정하고, 마이페이지에서 세 값을 수정한다.
- FE는 캐릭터 정적 자산 10개와 배경 HEX 10개를 소유한다. BE는 고정 ID와 회원 선택만 저장한다.
- 온기모드 방문 후기는 작성자의 최신 프로필을 반환한다. 프로필을 바꾸면 기존 게시글에도 즉시 반영하며 내부 회원 ID는 공개하지 않는다.
- DB: V040 `identity_member_profiles`를 추가하고 기존 회원을 안정적인 익명 이름·캐릭터·배경으로 backfill한다. 회원 삭제 시 cascade하고, 이름과 정확한 `01..10` ID 범위를 CHECK로 제한한다.
- API: `GET /api/v1/members/me`가 non-null 프로필 세 값을 반환하고, `PATCH /api/v1/members/me`가 CSRF를 요구하는 부분 수정을 제공한다. 명시적 null, 빈 요청, 알 수 없는 필드와 범위 밖 ID는 `400 VALIDATION_ERROR`다.
- 후기: 생성·목록 `VisitReview.author`는 `{displayName,characterId,backgroundId}`만 제공한다. 페이지 작성자 ID를 deduplicate해 한 번에 조회하고 프로필 누락 시 `탈퇴한 여행자`/01/01로 대체한다.
- 계약: Identity·VisitReview OpenAPI, fixture, Kakao 가이드와 [FE 전달서](docs/toFE/member-profile-api-handoff-2026-10-03.md)를 갱신했다. [OnMaru-Frontend #292 최종 명세 댓글](https://github.com/YRootLab/OnMaru-Frontend/issues/292#issuecomment-5969189832)과 [Backend #552](https://github.com/YRootLab/OnMaru-backend/issues/552)에 최종 방향과 상호 링크를 기록했다.
- 검증: 관련 identity/community/JDBC/Spring 집중 회귀 `BUILD SUCCESSFUL`(2m 13s), `DatabaseMigrationContractTests` 수정 후 단독 `BUILD SUCCESSFUL`, contract/R1/R2/Journey gate 통과, Node 127개 통과, AI 304개 통과·1개 skip, `git diff --check`와 branch parser #552 통과.
- 전체 `./gradlew test --no-daemon`: 523개 중 최초 3개 실패·2개 skip. 프로필 migration 최신 버전 기대값 누락 2건(`DatabaseMigrationContractTests.migratesEmptyDatabaseToLatestBaseline`, `upgradesPreviousBaselineToLatestWithoutLosingRows`)은 수정 후 통과했다. 남은 1건은 작업 전 baseline에서도 재현된 ARM64 호스트의 amd64 PostGIS 에뮬레이션 성능 예산 실패 `JdbcTourApiCatalogPublisherTests.thirtyThousandPublishedPlacesStayWithinMapInfoLatencyBudgets`다. 기능 정확성 검증과 CI `verify`는 별도로 확인한다.
- 남은 위험: 실제 운영 DB에서 V040 migration/backfill과 잠금 시간을 staging에서 확인하고, FE의 10개 캐릭터·10개 색상 및 100개 조합 대비/unknown-ID fallback을 통합 smoke해야 한다.
- 다음 단계: work-log cleanup 후 `develop` 대상 PR을 만들고 CI `verify`를 확인한다. 배포 뒤 기존 후기의 최신 프로필 반영과 프로필 PATCH를 staging에서 smoke한다.

## 2026-10-03 Issue #592 운영 관리자 로그인 세션 저장 수정

- 브랜치: `fix/592-admin-session-timestamp` (최신 `origin/develop` 기준, branch parser #592).
- 운영 증거: 올바른 관리자 로그인 요청에서 `last_login_at`은 갱신되지만 `identity_admin_sessions`는 0건이고 HTTP 401이 반환됐다. FE는 실제 `/auth/csrf`와 `/api/v1/auth/admin/login`을 호출하고 있었다.
- 원인: PostgreSQL JDBC 42.7.13은 `PreparedStatement.setObject(java.time.Instant)`의 SQL 타입을 추론하지 못한다. `JdbcAdminSessionStore`의 생성·회전·폐기 시간 파라미터를 UTC `OffsetDateTime`으로 변환한다.
- TDD: 실제 PostgreSQL 회귀 테스트에서 기존 구현의 `Can't infer the SQL type ... java.time.Instant` 실패를 확인한 뒤, 세션 생성·회전·폐기 3개 테스트가 통과하도록 수정했다.
- 전체 Java 검증: 522개 중 521개 통과, 2개 skip. 유일한 실패는 ARM64 호스트에서 amd64 PostGIS 이미지를 에뮬레이션한 기존 3만 건 map-info p95 테스트(381.03ms > 200ms)이며 단독 재실행도 같은 환경 경고와 함께 실패했다. 관리자 세션 대상 테스트와 `bootJar`는 통과했다. amd64 GitHub Actions `verify`를 병합 gate로 사용한다.
- 다음 단계: PR을 `develop`에 병합하고 Issue 상태를 정리한 뒤 다음 patch release branch로 `master`에 승격한다. 새 이미지 배포 후 운영 관리자 로그인 200, refresh session 생성, 컨테이너 health를 확인한다.

## 2026-10-02 Issue #568 OnMaru pipeline benchmark Skill

- 브랜치: `feature/554-monitoring-related`; 정본은 `skills/onmaru-ci-benchmark-experiment/` 하나이며 `.agents/skills` 복사본을 만들지 않는다.
- 기본 동작은 dry-run이다. 실제 dispatch는 현재 대화에서 명시적으로 요청된 경우에만 helper의 `--authorize-dispatch`를 사용하며, 응답 유실·모호 상태에서는 절대 재시도하지 않는다.
- 고정 Toolkit ref: `7ecbb89aae771604d9c1c532cf123f239e279110`. #555 Cloud 첫 전송에서 traces HTTP 200, DELTA histogram metrics HTTP 400을 관찰했고 Toolkit #137/PR #138에서 CUMULATIVE snapshot으로 수정했다. 같은 실제 source payload의 metrics도 HTTP 200으로 수용됨을 확인했다. 정식 workflow replay와 #556 dispatch·attestation은 pin의 develop/master 승격 뒤 수행한다.
- 2026-10-03 CI hygiene 보완([#554](https://github.com/YRootLab/OnMaru-backend/issues/554), [#555](https://github.com/YRootLab/OnMaru-backend/issues/555), 브랜치 `feature/554-monitoring-related`): 암묵적 `/tmp` Toolkit 선택을 제거하자 저장소 offline 스텁에서 16개 중 5개가 실패했다. 스텁의 replay schema·endpoint 검증·시각과 무관한 중복 identity·`ci_job=other`를 pin의 소비 계약과 맞췄다. 기본 경로 contract를 추가하고 기존 replay 테스트를 시각 변경으로 강화했다. 기본 clean-env 집중 17/17, 명시적 pinned-source 집중 17/17, 기본 전체 Node 213/213, contracts·Python compile·diff check가 통과했다. 보고서: `.superpowers/sdd/2026-10-02-ci-observability-benchmark-skill/ci-hygiene-fix-report.md`.
- 이전 세션에서 로컬 Toolkit의 성공/실패 synthetic round-trip과 dashboard query를 확인했고, outage range race는 Toolkit #133 / PR #134로 수정·병합됐다. 이번 최종 수정에서는 실제 stack·Cloud를 다시 검증하지 않았다.
- 2026-10-03 최종 수정(#554/#555/#568): replay checkpoint는 동일 저장소의 default branch에서 실행된 `workflow_run`/수동 replay와 검증된 default-branch SHA 이력만 신뢰한다. checkpoint가 없거나 손상된 최근 diagnostic은 건너뛰어 이전 유효 상태를 복원하거나 새 상태로 export한다. Skill 설치 확인은 consumer cwd package를 실행하지 않으며 Basic/Bearer payload를 전체 마스킹한다. 문서의 job query는 실제 `ci_job="other"` label과 맞췄다.
- 최신 검증: 고정 Toolkit `7ecbb89aae771604d9c1c532cf123f239e279110` checkout을 `ONMARU_TOOLKIT_SRC`로 지정해 집중 Node 36/36, 전체 Node 212/212가 통과했다. `bash scripts/verify-contracts`, Skill quick validation, 두 Python helper syntax, `git diff --check`, branch parser(`#554`)도 통과했다. 동시에 수행한 초기 실행의 타이밍 민감 테스트 실패와 단독 재실행 결과는 `.superpowers/sdd/2026-10-02-ci-observability-benchmark-skill/final-fix-report.md`에 기록했다. Java 전체 module test/`bootJar`와 workflow lint는 이번 최종 수정에서 별도 재실행하지 않았다.
- 외부 gate: Grafana Cloud credential과 endpoint는 준비됐고 실제 metrics/traces 수용까지 확인했다. 남은 항목은 정식 workflow replay의 ACK 증적, dashboard/Tempo 원본 연결, 승인된 outage/replay, series/span/retention/cost 기록, release baseline/candidate 3+3 Actions 검증 및 pipeline signed attestation이다. #555를 우회하지 않고 #556만 최초 통합 실행에서 허용하는 Toolkit #135/PR #136의 제한된 bootstrap gate도 병합됐다. Fixture나 직접 payload 수용만으로 Issue를 완료 처리하지 않는다.
- 새 개선율은 아직 확정하지 않았다. 기존 470초 serial과 411.62초 critical-path는 경계가 달라 12.42% 전체 CI 개선으로 주장하지 않는다. workflow가 기본 브랜치에 존재하고 #555/#556 gate 순환을 정리한 뒤 동일 조건 baseline/candidate 3회씩의 whole-workflow 중앙값·범위·실패율을 기록한다.
- Toolkit #115/#122 및 Agent Toolkit #58 ownership 링크 변경은 OnMaruBE Skill PR merge 뒤의 후속 작업이다.
- 메인 `README.md`에 일상 CI 관측과 수동 3+3 benchmark 흐름, Docker의 역할과 설치 경계, 로컬 dashboard 실행·정리, Skill dry-run/dispatch/wait/compare, 개선율 해석을 추가했다.
## 2026-10-03 Issue #572 페이지네이션 totalCount

- 브랜치: `fix/572-paginated-total-count` (branch parser #572).
- 현상: 운영 `GET /api/v1/odii/stories?language=ko-KR&limit=20`은 20개와 `hasMore:true`를 반환하지만 필터 적용 후 전체 건수 필드가 없어 FE가 전체 조회·지역별 조회 규모를 표시할 수 없다.
- 변경: Odii, 한옥/장소, 방문 후기, 저장 리소스·여정, Journey thread·timeline의 cursor/limit 응답에 `totalCount`를 추가한다. 값은 cursor 적용 전, 현재 필터와 공개/가용성 조건을 적용한 전체 건수다. OpenAPI·fixture·FE 문서를 함께 갱신한다.
- DB 경로: Odii JDBC adapter는 동일한 repeatable-read transaction에서 실제 선택 언어의 활성·공개·재생 가능 story count를 조회한다.
- 관리자 `AdminPage` 계열은 별도 count query와 운영 성능 검증이 필요해 Issue #573으로 분리했다.
- 검증: 서비스 단위 테스트와 Spring API 대상 경계/JDBC 테스트, 전체 contract/R1/R2/Journey E2E fixture 검사, `git diff --check`, branch parser가 통과했다. 전체 `./gradlew test`는 다른 작업공간의 PostgreSQL 테스트와 경합해 기존 `JdbcTourApiCatalogPublisherTests.thirtyThousandPublishedPlacesStayWithinMapInfoLatencyBudgets`에서 DB 예외가 발생했다. 경합 종료 후 해당 테스트를 단독 재실행하자 DB 예외는 사라졌지만 ARM64 호스트의 amd64 PostGIS 에뮬레이션 환경에서 viewport p95가 `822.919333ms`로 `500ms` 기준을 초과했다. 변경 범위 대상 테스트는 모두 통과했다.
- 사용자 승인: ARM64 로컬의 amd64 PostGIS 에뮬레이션 성능 실패를 PR에 명시하고, amd64 GitHub Actions의 `verify` 통과를 병합 조건으로 PR을 생성한다.
- 최신 `origin/develop` 병합 후 audio/catalog/community/journey 모듈 테스트, Odii JDBC·웹 경계 테스트, 전체 contract/R1/R2/Journey E2E fixture 검사, `git diff --check`, branch parser가 통과했다.
- 다음 단계: PR `verify` 확인과 배포 후 운영 API smoke를 수행한다.

## 2026-10-02 Backend 병렬 정리 (#256 외 12건)

- 브랜치: `feature/256-backend-batch` (`origin/develop` 최신 기준, branch parser #256).
- 요청 범위: #256, #265, #375, #382, #392, #486, #500, #509, #518, #519, #520, #521, #545.
- 구현 커밋: `9618d5d` quota anchor, `5191eff`/`698a5f1`/`1b72e26` FastAPI narration stream·보안 경계, `4058a3c` #382 Odii 이력, `aff90cf` #486 목록→상세 계약, `37aa390` #518/#520 quota·SSE 계약, `8c9e982` Spring stream relay.
- #382: lifecycle/phase와 `fetched/mapped/staged/published/tombstones`를 DB·안전 로그에 남기고 production profile + JDBC + HTTP fixture와 AWS runbook을 추가했다. 로컬 AC와 교차 리뷰는 통과했으며 실제 Lightsail 로그·readonly SQL·공개 `trending-sounds` smoke는 배포 후 gate다.
- #486: 목록에 노출되는 category/name/overview 기반 한옥은 `/api/v1/hanoks/{id}` 상세에서도 조회되며 원 category를 보존한다. production 경로 리뷰는 통과했다. 기존 demo seed의 목록/상세 불일치는 비차단 후속이다.
- #518/#520: 2026-10 KST 회원 quota 2회, guest AI 401, exempt total bypass+active 1, Journey 전용 429 alias, FastAPI Gemini SSE → Spring → browser `run.text.delta`, candidate allowlist·timeout·fallback·cancel 경계를 구현했다. 실제 Gemini tier, first-delta latency, proxy buffering, 배포 설정과 snapshot smoke는 staging gate다.
- 이미 구현되어 운영 검증 중심인 항목: #256, #265, #375, #509, #519, #521 일부, #545 일부. #392는 staging secret·실제 수집/ACTIVE revision/API 증거가 필요하다. #545는 로그인·쓰기·SSE와 workflow 실제 rollback이 남아 있다.
- #500은 `develop`에는 이미 fail-closed지만 현재 `master`에만 Trivy `continue-on-error` 두 곳이 남아 있다. Git Flow상 이 브랜치에서 고치지 않고 `master` 대상 별도 hotfix/보호 PR로 처리해야 한다.
- 최종 로컬 검증: Admission/Exploration/Journey Gradle 회귀 `BUILD SUCCESSFUL`(3m 5s), AI `304 passed, 1 skipped`, ruff/mypy PASS, 전체 contract와 R1/R2/Journey E2E fixture PASS, `git diff --check` PASS.
- 다음 단계: PR 전 work-log cleanup, PR `verify`, staging 운영 gate를 수행한다. Issue는 실제 운영 AC를 충족하기 전 닫지 않는다.
- 로컬 미추적 `.agents/`, `.claude/`, `skills-lock.json`은 기존 사용자 작업물이며 이번 변경에 포함하지 않는다.
## 2026-10-03 Issue #566 지도 정보모드 cursor·한옥 필터 수정

- 브랜치: `fix/566-map-info-cursor-hanok`.
- 운영 `/map`은 신규 `/api/v1/map/info/*` 대신 2026-10-02 FE 커밋 `8468e52`에서 복구된 구형 `useMapData → /api/map/places` 경로를 사용한다. BE legacy adapter가 100건으로 제한한 뒤 FE가 거리·카테고리로 재필터링해 화면에는 100건 이하만 보인다.
- map-info cursor는 마지막 `.`만 서명 경계로 분리해 거리값 `0.0`이 있는 정상 cursor의 두 번째 page 400을 수정했다.
- 지도 public category에 `HANOK`을 추가했다. 목록·viewport 모두 `HANOK/HANOK_STAY/HANOK_CAFE/HANOK_EXPERIENCE` 원천 category만 union하며, 원거리 DISTRICT·REGION도 같은 조건으로 직접 집계한다.
- controller·service·실제 PostgreSQL/PostGIS 통합 테스트에서 목록, PLACE, DISTRICT, REGION과 비한옥 제외를 검증했다. OpenAPI와 fixture도 갱신했다.
- 상세 재현·코드 맥락·해결 결과는 `troubleshooting-worklog/26.10.03 map-info-100-item-and-hanok-filter-regression.md`에 기록했다.
- FE 파일별 변경사항, 목표 API 계약, 필수 테스트와 BE 준비 완료 조건은 `docs/toFE/map-info-regression-fix-handoff-2026-10-03.md`에 전달 문서로 분리했다.
- 다음 단계: PR·staging 배포 후 실제 두 번째 cursor 200과 `category=HANOK` list·viewport 응답을 확인하고, FE 신규 경로 전환 뒤 운영 검증한다.

## 2026-10-01 Issue #561 운영 온기 히트맵

- 브랜치: `fix/561-warmth-heatmap`.
- 현상: 운영 온기 조회가 오래 걸리고, 서울·부산 `강서구`가 같은 잘못된 위치에 표시된다. 기본 날짜를 오늘로 보내는 FE 문제는 OnMaru-Frontend #267에서 별도 수정 중이다.
- 변경: 지역마다 활성 장소를 반복 조회하던 SQL을 활성 장소 중심의 집계로 바꾸고, 지역 ID 또는 부모 시도·시군구 주소가 정확히 맞는 좌표만 사용한다. PostgreSQL 회귀 테스트에서 동명 시군구의 좌표 분리를 검증한다.
- 검증: `./gradlew :apps:spring-api:test --tests '*JdbcVisitorObservationStoreTests' --no-daemon` 통과.
- 다음 단계: PR `verify`, 운영 규모 성능 확인, release/master 배포 후 실제 온기 응답 검증.

## 2026-10-01 Issue #553 운영 지도 장소 목록 500 복구

- 브랜치: `fix/553-map-projection-backfill`. 운영 `map/info` 화면이 500이었고, V036 이전에 게시된 활성 TourAPI revision의 지도 projection/publication이 비어 있었다.
- 운영 조치: 변경 전 PostgreSQL dump를 `/opt/onmaru/backups/onmaru-before-map-backfill-20261001.dump`에 보관하고 `pg_restore --list`를 확인했다. 원천 23,675건을 변경하지 않고 projection 23,675건 및 publication을 게시했다. `ANALYZE` 후 정보 목록·뷰포트 API가 200으로 응답했다.
- 코드: V037 idempotent backfill, 지역 코드가 없는 장소의 projection 게시, 통합 회귀 테스트, migration registry 및 schema 문서. Issue #553에 연결한다.
- 다음 단계: PR CI 확인 및 develop 병합. 사용자 브라우저 새로고침으로 지도 목록 표시를 최종 확인한다. 운영 서버에는 SQL이 직접 적용됐으며 다음 릴리스에서는 V037이 기존 publication을 확인하고 건너뛴다.

## 2026-10-01 Issue #545 온디맨드 스테이징

- 현재 브랜치: `feature/545-staging-deploy`. 사용자 결정: 기존 1GB/$7 Lightsail에 운영은 상시, 분리된 스테이징 Spring·PostGIS는 FE 개발자가 제한 SSH 명령으로 필요할 때만 실행하고 2시간 후 자동 중지한다.
- 완료: PR #546·#549·#550을 `develop`에 반영했다. 실서버에서 분리된 DB와 Flyway 35개 migration, 합성 장소, Spring health·CSRF·지도 조회를 검증하고 스테이징을 중지했다. 운영 컨테이너는 healthy다. Vercel Preview API URL·FE Preview 배포와 `develop` 브랜치에 묶인 `staging.onmaru.site` 별칭을 준비했다. GitHub `STAGING_SPRING_URL`은 새 API 주소로 변경했다. 상세 증적은 Issue #545에 기록했다.
- 이번 변경: CI `verify`·이미지 검사·migration gate 이후 검증된 `develop` 이미지 digest를 잠든 Lightsail 스테이징에 준비하는 수동 배포 job과 제한 SSH 배포 계정을 추가한다. FE의 `start`만 컨테이너를 실행한다.
- 다음 단계: 이 PR의 `verify` 확인·병합, CI 전용 공개키/비밀키 설정, 실제 수동 배포 검증. 사용자가 DNS는 나중에 추가하기로 해 API/FE 공개 TLS는 대기 중이다. FE 개발자 **공개키**와 Kakao staging callback, 로그인·쓰기·SSE 검증도 남아 있다. Issue #545는 열어 둔다.

## 2026-10-01 Issue #542 Toolkit 3회 정책 커밋 적용

- 브랜치: `fix/542-toolkit-three-run-pin`
- 범위: Module Benchmark caller의 reusable workflow `uses`와 `toolkit_ref`를 Toolkit PR #116 병합 커밋 `f3d5f4c244b42260a6b9ae9c1f7c76e6550f525a`로 함께 고정한다.
- 검증: caller 계약 테스트는 변경 전 통과했고, 새 SHA에 대한 실패를 재현한 뒤 통과했다. 전체 Node 테스트 127개와 `bash scripts/verify-contracts`가 통과했다. PR `verify`와 실제 Module Benchmark 실행을 확인한다.
- 남은 일: 현행 reusable workflow는 `develop` 모드에서 원본 증적을 모으지만 release 3회 비교 함수를 호출하지 않는다. 이 판정 연결은 [#543](https://github.com/YRootLab/OnMaru-backend/issues/543)에서 추적한다.

## 2026-10-01 Issue #509 관리자 목록 페이지네이션

- 브랜치: `feature/509-admin-pagination`; 구현 커밋 `e10057f`와 최신 `origin/develop` 병합 완료.
- 이번 세션: 빈·비정규 Base64 cursor 거부, 운영 queue 기본 limit 20, 회원 상태 필터 `ACTIVE|DELETING` 정정, cursor secret 시작 시 강도 검증, 좌표 누락 후기의 SQL 페이지 경계 수정, runtime 의존성 lockfile 보정, FE 연동 문서와 OpenAPI 정합성 보강.
- 추가 구현: 후기 `status + query`(본문·장소명) SQL 검색과 신고 `reason` SQL 필터를 cursor에 바인딩했다. 관리자 목록의 손상 cursor는 HTTP `400 VALIDATION_ERROR` 테스트로 확인하고, 후기·신고·dashboard·moderation queue의 전체 snapshot 미호출 경계 테스트를 추가했다.
- 검증: 병합 후 community 테스트, 관리자 cursor/API/dashboard·JDBC 후기/신고 대상 Spring 테스트, `bootJar`, `bash scripts/verify-contracts`, migration policy, `git diff --check` 통과. 앞선 전체 `check`는 453개 중 기존 map-info 3만 건 latency 테스트 1개가 ARM64의 amd64 Docker 에뮬레이션 환경에서 1085ms/500ms로 실패했다. 해당 테스트의 앞선 단독 재실행은 통과했다.
- PR: [#528](https://github.com/YRootLab/OnMaru-backend/pull/528)을 `develop` 대상으로 열었다. `Refs #509`로 연결했고, #509는 운영 유사 성능 및 배포 검증까지 열린 상태로 둔다.
- 다음 단계: PR `verify` 결과와 리뷰를 확인한 뒤 병합한다. staging 운영 유사 데이터 검증은 별도로 진행한다.
- 남은 운영 검증: staging 운영 유사 데이터의 `EXPLAIN (ANALYZE, BUFFERS)` 및 latency/heap 전후 비교, secret 설정과 배포 smoke.

## 현재 작업: Lightsail 배포 준비 (#519)

- 기준일: 2026-10-01
- 브랜치: `feature/519-lightsail-deployment` (branch parser 결과: #519)
- Git Flow: 기능 변경은 `develop` PR로 반영하고, 실제 운영 배포는 release 흐름을 거친 `master` 이미지 기준으로 한다. 이 PR은 배포 문서와 수동 배포 scaffold를 제공하며 #519를 닫지 않는다.
- 현재 GitHub 상태: 커밋 `73dd517`, `307dfe2`를 `origin/feature/519-lightsail-deployment`에 push했고 [PR #523](https://github.com/YRootLab/OnMaru-backend/pull/523)을 `develop` 대상으로 열었다. #519는 AWS 실배포가 남아 있어 `Refs`로 연결했고 계속 open 상태다. PR은 mergeable이며 CI가 아직 진행 중이므로 merge 전에 최신 `verify` 결과를 확인한다. CodeRabbit 자동 리뷰는 공개 저장소의 수동 리뷰 요구로 건너뛰었다.

### 이번 변경

- `infra/lightsail/`: Nginx/Spring/PostGIS Compose, 역할 분리 init/restore, DB backup, HTTP ACME bootstrap, HTTPS proxy, Certbot renewal timer, 비밀값 없는 `.env.example`, 실행 README를 준비했다.
- `docs/operations/runbooks/lightsail-render-cutover.md`: 현재 AWS 상태부터 DB 복원, HTTPS, FE 검증, 전환·rollback 및 Render 종료 조건까지 기록했다.
- Spring CORS를 환경변수 allowlist로 바꾸고 `/auth/csrf`에도 적용했다. CSRF 필터가 같은 Origin 정책을 사용하며 forwarded-header 처리 설정을 추가했다.
- `Dockerfile`의 JVM 메모리 제한을 컨테이너 메모리 기준 환경변수로 전달하도록 조정했다.
- 로컬 `.agents/`, `.claude/`, `skills-lock.json`은 기존 미추적 작업물이며 이번 PR에 포함하지 않는다.

### 검증 기록

- 기존 로컬 검증: Lightsail Compose config parse, Spring `:apps:spring-api:compileJava`, 셸 스크립트 syntax, YAML parse, `git diff --check` 통과.
- PR #523 CI의 Java/Module Benchmark 실패는 `AdmissionWebBoundaryTests`의 신뢰하지 않는 `X-Forwarded-For` 검사에서 재현했다. `server.forward-headers-strategy=framework`가 신뢰 프록시 검사 전에 원격 주소를 덮어쓴 것이 원인이다. 기본값을 `none`, Lightsail Compose를 `native`로 바꾸고 Nginx가 클라이언트의 `X-Forwarded-For`를 덮어쓰도록 수정했다. 대상 테스트는 수정 전 1건 실패, 수정 후 통과했다. 전체 unit/contract shard, Compose config parse, `git diff --check`도 통과했다. 커밋 `75f38d4`의 CI `verify`는 통과했다.
- 사용자 요청으로 일반 PR에서 Module Benchmark가 자동 실행되는 문제를 #524로 기록했다. `pull_request` 트리거를 제거하고 CI·테스트 관련 파일이 `develop`에 반영될 때만 자동 실행되도록 `push.paths`를 제한했다. 수동 실행은 유지했다. 진행 중이던 PR benchmark run `36796514768`은 취소 요청했다.
- runner 구조와 Java job 표시 개선은 #525로 기록했다. CI job 표시 이름을 `Java 모듈 및 Spring API 전체 테스트`로 구체화했다. 기존 모듈별 matrix 측정은 전체 workflow 소요 시간이 더 길었고, GitHub-hosted runner는 job마다 종료된다. 병렬 실행 방식은 사용자 선택 후 별도로 결정한다.
- 실제 Lightsail Nginx/TLS, Render DB 복원, FE 로그인·쿠키·CORS, 운영 트래픽 전환은 아직 검증하지 않았다.

### AWS 진행 상태

- 인스턴스: `onmaru-prod-seoul`, Seoul `ap-northeast-2a`, Ubuntu 24.04, $7/월 번들(1 GB RAM, 2 vCPU, 40 GB SSD).
- 고정 IP 리소스: `onmaru-prod-seoul-ip` 연결 완료. 숫자 IP는 사용자가 콘솔에서 확인해야 하며 아직 DNS에 등록하지 않았다.
- 방화벽: TCP 80 공개, TCP 443 공개, TCP 22는 전체 IPv4/IPv6 및 Lightsail 브라우저 SSH로 열려 있다. 22 제한은 별도 로컬 SSH 경로를 확인한 뒤 production 전에 적용한다. 8080/5432는 열지 않는다.
- 인스턴스 패키지 업데이트 후 재부팅했다. 사용자가 브라우저 SSH 재접속, Docker/Compose 설치 및 Docker 서비스 `active` 확인, `/opt/onmaru` 생성과 `ubuntu` 소유권 설정을 완료했다.
- Docker 설치는 확인됐지만 `hello-world` 컨테이너 성공 여부는 아직 보고되지 않았다. 저장소/배포 파일, `.env`, 컨테이너, PostgreSQL 데이터, Nginx/TLS는 서버에 아직 없다.
- Automatic Snapshots는 비용을 고려해 꺼져 있다. PostgreSQL 별도 backup/restore 절차는 필수다.

### 다음 작업과 순서

1. PR #523의 `verify` CI와 PR diff를 확인하고, merge 전에 변경 사항을 검토한다.
2. PR 반영 뒤 GitHub workflow에서 사용할 이미지 SHA와 GHCR 접근 방식을 확인한다.
3. 서버에 배포 파일을 가져와 비밀값을 `.env`에 설정하고 `chmod 600`을 적용한다.
4. PostgreSQL/PostGIS만 먼저 시작하고 Render 원본 dump를 안전하게 복원·검증한다. 그 전에는 Spring을 시작하지 않는다.
5. Spring/Nginx를 구성하고 HTTP challenge로 TLS를 발급한 다음 HTTPS, health, CORS/CSRF, Kakao, SSE를 확인한다.
6. 그 뒤 `api.onmaru.site` DNS 및 FE Preview를 검증한다. 운영 전환 전까지 Render API/DB, Vercel `@`/`www`, Render Kakao callback을 유지한다.
7. 실제 운영 전환은 `develop` 직접 배포가 아니라 승인된 release 절차를 거친 `master` 이미지로 수행한다. DB 백업/복구 및 rollback 조건 확인 전 Render 자원을 종료하지 않는다.

## 남은 운영 위험

- Lightsail 한 대에 API와 DB가 함께 있어 호스트 장애 시 둘 다 중단되는 단일 장애 지점이다. 현재는 저비용 테스트/초기 운영 목표다.
- SSH 22 인바운드가 넓게 열려 있다. 접속 경로를 보존하며 source IP를 제한해야 한다.
- $7 번들은 1 GB RAM이라 Spring, PostgreSQL/PostGIS, Nginx 메모리 사용량을 관찰하고 OOM이 반복되면 상향을 검토한다.
- Render 데이터의 실제 DB 버전/용량과 dump/restore 결과, GHCR package visibility, 필요한 운영 secret은 아직 확인하지 않았다.

## 2026-10-04 Spring 운영 Blue-Green CD 준비 (#586)

- 브랜치/worktree: `feature/586-lightsail-blue-green-cd`, `.worktrees/feature-586-lightsail-blue-green-cd`. 사용자 요청에 따라 GitHub Issue에는 이번 내용을 작성하지 않았다.
- 기준: 최신 `origin/develop`에서 작업했다. `develop`은 자동 운영 배포하지 않으며, release 흐름을 거쳐 `master`에 반영된 Spring image만 운영 CD 대상으로 삼는다. FastAPI는 별도 Lightsail 배포 대상으로 남겨 두었다.
- 구현: Blue/Green Spring slot, Nginx runtime upstream, 1GB 호스트용 메모리·swap·OOM gate, 제한 SSH deployer, immutable digest 배포, 공개 smoke·70초 drain·비정상 종료 rollback, `master` 전용 production job을 추가했다. 스테이징이 실행 중이면 운영 배포와 rollback은 fail-closed로 중단한다.
- CD 보강: Repository Variable 활성화 gate, build 전 `status <sha>` no-op preflight, 성공 후 수동 rollback, 첫 전환의 legacy slot 복귀, 거부 SHA hold, 성공·실패·no-op webhook, 168시간 경과 dangling image 정리, 제3자 Action full commit SHA pinning을 추가했다. DB migration은 자동으로 되돌리지 않는다.
- PR 전 독립 리뷰 보강: runner image에 build SHA를 보존하고, traffic 전환 뒤 drain 전체 구간의 health·OOM·memory·swap을 재검사한다. 배포 상태는 generation directory와 `current` symlink의 단일 원자 교체로 확정하며, 이전 slot 중지 성공 뒤에만 commit한다. 실패 복구에서는 이전 slot health가 확인된 경우에만 route·env를 되돌려 정상 후보를 잘못 중지하지 않는다. webhook은 build와 migration 실패 단계도 구분한다.
- 시작 부하: Blue/Green 후보의 TourAPI·Odii `ApplicationReadyEvent` 동기화를 끌 수 있는 설정과 회귀 테스트를 추가했다. 정기 cron과 기존 DB lease/fence 정책은 유지한다.
- 실서버 리허설: 운영 route를 바꾸지 않고 동일 image의 Green을 384MB 제한으로 약 60초 겹쳤다. 약 30초 후 healthy, Green 약 212MB, swap 약 114MB 증가, 최저 `MemAvailable` 약 156MB, 기존 운영 health 200, OOM 없음이었다. 이는 무부하 중첩 증거이며 실제 새 image 전환이나 부하 상태 검증은 아니다. 리허설 컨테이너는 제거했고 운영은 healthy 상태를 확인했다.
- 블로그 초안: `docs/drafts/2026-10-04-lightsail-blue-green-cd.md`. Render+Neon에서 Lightsail로 옮긴 배경, Render 재활용의 DB/네트워크 문제, virtual memory와 thrashing, 측정 결과에 더해 GitHub variable 평가 시점, 두 종류 rollback, 예약 재배포를 막는 hold, image reference 기반 보존, Action supply-chain 경계를 서사로 정리했다. `blog-tone`과 `writing-rule`을 적용했다.
- 검증: 전체 Node 223/223, production/staging 배포 계약 14/14, startup-sync·Odii PostgreSQL 집중 Spring 테스트 `BUILD SUCCESSFUL`, Compose config, workflow YAML parse, production shell `sh -n`, 두 skill quick validation, 문서 계약, `git diff --check`가 통과했다. 현재 환경에 ShellCheck와 Actionlint 실행 파일이 없어 이번 보강 뒤에는 재실행하지 못했으며 PR의 CI `verify`를 최종 gate로 사용한다.
- 아직 활성화하지 않음: 운영 서버의 최초 `bootstrap-blue-green.sh`, production 전용 Ed25519 key 설치, Repository Variable `PRODUCTION_DEPLOY_ENABLED`, production environment의 `PRODUCTION_DEPLOY_SSH_KEY`/`PRODUCTION_DEPLOY_WEBHOOK_URL` secret과 `PRODUCTION_SSH_KNOWN_HOSTS` variable 설정, 실제 `master` 배포는 남아 있다. 첫 merge가 준비 없이 배포되지 않도록 활성화 flag 기본값은 false다.
- 배포 시점 변경: `master` push는 build·scan·migration 검증까지만 수행한다. 실제 운영 배포는 매일 `03:17 KST` schedule 또는 `deploy_production=true`인 명시적 `workflow_dispatch`에서만 실행한다. 예약·수동 실행은 먼저 server SHA·clean master·public health를 검사하고 같으면 build 전 no-op 처리한다.
- 서비스 범위: `master` 운영 경로는 Spring image만 build·scan·배포한다. FastAPI image 단계는 `develop` 스테이징 실행에만 두고, 별도 Lightsail CD 설계 전까지 운영 경로에서 제외한다.
- 프로젝트 skill: `skills/onmaru-production-deploy/SKILL.md`이며 로컬 discoverability용 사본은 `~/.codex/skills/onmaru-production-deploy`에 있다. “온마루/AWS/Lightsail 운영 배포해줘” 또는 `$onmaru-production-deploy` 직접 호출에서 `deploy.yml`을 `master`/`deploy_production=true`로 한 번 dispatch하고 결과를 관찰한다. `develop`의 release/master 승격은 사용자가 함께 명시했을 때만 선행한다. 설명·상태·스테이징 요청은 배포 권한으로 해석하지 않는다.
- Issue 상태: #586은 이번 CD 보강 외에도 SSH 22 `/32` 제한, 실제 backup 격리 restore, rollback 기간과 Render·Neon 정리 결과를 완료 기준으로 가지므로 이 PR에는 `Refs #586`을 사용하고 merge 뒤에도 해당 운영 증거가 생길 때까지 열어 둔다.
- release 전 staging 재검증에서 production preflight의 의도된 `skipped`가 간접 의존성으로 전파되어 migration/staging deploy까지 skip되는 현상을 재현했다. `migration-gate`가 image build 성공을 명시적으로 판정하도록 `always()` 조건을 추가하고 회귀 계약 테스트를 남겼다.
- 후속 Actions run `37178635461`에서 image build와 migration gate는 성공했지만 같은 skip 전파가 `staging-deploy`에도 남아 있음을 확인했다. staging deploy/smoke가 `always()`에서 직접 build·migration 성공을 판정하도록 보강하고 두 job의 회귀 계약을 추가했다.

## 2026-10-05 Issue #543 release module benchmark evidence

- 브랜치: `docs/543-release-benchmark-evidence`; 기준: PR #633 merge commit `648d3bd`가 반영된 최신 `origin/develop`.
- 범위: v0.3.38/v0.3.39의 `Module Benchmark`를 각각 서로 다른 3회 실행하고, 같은 `spring-api-postgres-other` module의 검토된 evidence를 Release asset으로 보존한 뒤 고정 Toolkit comparator로 release 판정을 재현한다.
- 관련 이슈: [#543](https://github.com/YRootLab/OnMaru-backend/issues/543). 완료 조건은 3+3 중앙값 비교, 15% 초과 회귀의 승인 보류, 원본 run/artifact link 보존, 단일 정본 판정 및 계약 테스트 통과다.
- 현재 증적: 여섯 실행이 모두 성공했다. v0.3.38 값은 467.05/341.36/394.40초, v0.3.39 값은 471.61/411.86/457.93초다. 중앙값 delta는 +16.108%로 `approval_hold`이며 자동 통과시키지 않는다.
- 상태: 두 Release asset과 v0.3.39 비교 asset 업로드, README/운영 보고서 반영, 정본 comparator 재실행과 계약 테스트 35개 통과. 이 문서 PR 병합 후 #543과 Toolkit #115를 종료한다.
