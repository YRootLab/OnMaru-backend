# handoff.md

## 2026-10-03 Issue #552 회원 익명 프로필·온기 후기 작성자

- 브랜치: `feature/552-member-profile` (branch parser #552).
- 사용자 결정: 카카오 닉네임·프로필 이미지는 사용하지 않는다. 가입 시 익명 이름과 `CHARACTER_01~10`, `BACKGROUND_01~10` 조합을 자동 배정하고, 마이페이지에서 세 값을 수정한다.
- FE는 캐릭터 정적 자산 10개와 배경 HEX 10개를 소유한다. BE는 고정 ID와 회원 선택만 저장한다.
- 온기모드 방문 후기는 작성자의 최신 프로필을 반환한다. 프로필을 바꾸면 기존 게시글에도 즉시 반영하며 내부 회원 ID는 공개하지 않는다.
- 설계: `docs/superpowers/specs/2026-10-03-member-profile-warmth-author-design.md`.
- FE 전달: [OnMaru-Frontend #292](https://github.com/YRootLab/OnMaru-Frontend/issues/292)에 캐릭터 자산 10종, 배경 HEX 10종, 마이페이지 수정 UI, 온기 후기 작성자 표시 계약을 기록했다. BE #552에도 최종 방향과 FE Issue 링크를 댓글로 남겼다.
- 다음 단계: 사용자 spec 검토 승인 후 implementation plan 작성과 TDD 구현.

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
