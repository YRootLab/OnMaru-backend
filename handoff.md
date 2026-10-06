# handoff.md

## 2026-10-06 Issue #643 외부 장소 VisitReview 설계

- 브랜치: `docs/643-external-place-review-design`, 관련 Issue: #643.
- 요청: FE Kakao 장소 검색 결과로 대한민국 내 모든 장소에 온기를 작성할 수 있도록 현재 API의 제약을 분석하고 장소·태그 validation 및 FE 오류 계약을 구체화한다.
- 결정: 백엔드는 Kakao API를 재조회하지 않는 MVP로 시작한다. 신규 `POST /api/v1/visit-reviews`에서 외부 장소를 resolve-or-create하고, 대한민국 bbox 밖은 거절하며 bbox 안의 region 미해결 장소는 `kr-unassigned`로 허용한다. 태그는 선행 `#` 하나만 호환 정규화하고 canonical 값에는 `#`을 저장하지 않으며, 잘못된 태그가 하나라도 있으면 후기 전체를 거절한다. FE와 BE는 같은 OpenAPI 정책으로 각각 UX validation과 최종 validation을 수행한다.
- 문서: `docs/superpowers/specs/2026-10-06-external-place-visit-review-design.md`.
- 다음 결정: 기존 revision Catalog에 즉시 게시하지 않는 Catalog-owned 경량 external-place registry의 영속 모델과 ownership을 우선 설계한다. 이후 기존 TourAPI 장소 중복, `kr-unassigned` 표현, 위치 충돌 거리, public ID, rate limit, FE rollout을 확정한다.

## 2026-10-06 Issue #647 스테이징 공개 API fixture

- 브랜치: `feature/647-staging-api-fixtures`, 관련 Issue: #647.
- 요청: 소리마루 한 기능이 아니라 로컬/스테이징 FE가 호출하는 공개 API 흐름에 응답할 합성 fixture를 스테이징 DB에 제공한다.
- 구현: 기존 장소 2건 seed를 장소 4건, 후기 3건(공개 2·숨김 1), Odii story 3건으로 확장하고 지도 projection·장소 상세 이미지/태그·후기·장소↔오디오 연결을 고정 ID로 구성했다. `p-staging-hanok-a`가 대표 cross-surface fixture다.
- 안전: `onmaru_staging` DB 이름 guard와 운영 데이터 미사용 원칙을 유지하고, 모든 insert는 재실행 가능하도록 conflict 처리를 둔다. 스테이징 Odii 공개 host는 fixture 음원용 `samplelib.com`만 추가했다.
- 검증: `:apps:spring-api:compileTestJava`와 `git diff --check` 통과. PostGIS에서 seed 두 번 실행·연결 row·비-staging 거부를 검증하는 `StagingFixtureTests`를 추가했다. 로컬 디스크 full 당시 Docker VM 로그 쓰기가 실패한 뒤 engine socket이 timeout 상태여서 Testcontainers 실행은 GitHub CI 게이트에서 확인한다.
- 다음 단계: 코드 리뷰 반영, PR CI `verify` 통과 후 develop 병합, Issue reconcile, 검증된 develop image 스테이징 반영과 실제 공개 API smoke를 수행한다.

## 2026-10-05 Release v0.3.41 (#641)
## 2026-10-05 FE 실시간 온기 요청 사전 검토

- 현재 워크스페이스 브랜치: `feature/realtime-related-fe-requests`. 다른 개발자가 이 브랜치에서 후속 구현을 이어갈 예정이므로 현재 이름을 유지한다. 다만 저장소의 branch parser 규칙상 Issue 번호가 없는 브랜치이므로 PR 생성 전에는 적절한 GitHub Issue를 연결하고 브랜치 정책 충족 방법을 정리해야 한다.
- 요청 원문: `/Users/yangseunghyeon/Downloads/onmaru-backend-request.md`. 이번 세션에서는 요청서를 읽고 현재 Spring·Lightsail·Nginx·CSRF·CORS 구성과 대조했으며, 애플리케이션 코드·인프라 설정·요청 원문은 변경하지 않았다.
- 검토 결론: Live Presence는 AI 기능이 아니고 기존 비즈니스 API 및 SSE 경계와 맞으므로 FastAPI가 아니라 Spring MVC `SseEmitter`로 구현하는 안을 권장한다. 저장소에도 Journey SSE의 `SseEmitter`, heartbeat, 종료 콜백 구현이 이미 있다.
- 문서 정정 필요: `SseEmitter`가 열린 연결마다 요청 스레드를 계속 점유한다는 설명은 부정확하다. Servlet async가 요청 스레드를 반환하지만 `send()`는 블로킹될 수 있으므로 작은 bounded 전송 executor, 유한 큐, 전송 timeout 및 느린 연결 제거가 필요하다.
- 인스턴스 제약: 운영 Lightsail은 약 909 MiB RAM이고 Compose 제한은 Spring 448 MiB, PostgreSQL 320 MiB, Nginx 64 MiB다. JVM은 Spring 컨테이너 RAM의 60%를 최대 heap 기준으로 사용한다. 전체 SSE 상한 200개는 확정 용량이 아니라 검증 목표로 취급하고, 초기에는 50~100개 상한으로 시작해 heap·CPU·executor queue·기존 API latency를 부하 테스트한 뒤 조정한다.
- Nginx 현황: 운영과 스테이징의 일반 `location /`에 이미 HTTP/1.1, 빈 `Connection`, `proxy_buffering off`, `proxy_request_buffering off`, `proxy_read_timeout 130s`가 적용돼 있다. 구현 시 `/api/v1/realtime/` 전용 `location`을 추가해 `proxy_cache off`, `gzip off`, 긴 read timeout과 동일한 신뢰 프록시 헤더 정책을 명시적으로 적용하는 안을 권장한다.
- CSRF 현황: 이 저장소는 Spring Security의 `SecurityFilterChain`이 아니라 자체 `CsrfProtectionFilter`로 `/api/**`의 unsafe method를 검사한다. 익명이고 쿠키 인증이나 사용자 상태 변경이 없는 `POST /api/v1/realtime/warmth`만 정확히 예외 처리할 수 있으며, `permitAll` 표현 대신 자체 필터 예외라고 문서화해야 한다. CORS는 abuse 방어가 아니므로 clientId/IP/room/전체 rate limit은 별도로 필요하다.
- CORS 현황: `/api/**`는 환경변수 기반 exact-origin allowlist와 `GET`, `POST`, `OPTIONS`를 이미 지원하지만 전역 `allowCredentials(true)`를 사용한다. 실시간 경로는 credentials 없이 운영 FE origin을 허용하고, `localhost:3000`은 운영 allowlist가 아니라 local 또는 staging 환경에서만 허용하는 방안을 권장한다.
- 관련 Issue 조회: #519는 Lightsail 배포·운영 검증, #520은 기존 FE→BE 전달사항이며 이번 Live Presence 구현 전체를 직접 추적하지 않는다. 실제 구현 전에 중복 Issue를 다시 확인하고, 없으면 API 계약·Spring 구현·Nginx·보안 경계·부하 검증 acceptance criteria를 포함한 Issue를 생성한다.
- 다음 단계: Issue/브랜치 정리 → 요청 계약 확정(`roomId` allowlist, 오늘 방문자 정의 포함) → Spring SSE/POST 및 bounded resource 구현 → CSRF/CORS 경계 테스트 → 운영·스테이징 Nginx 전용 경로 추가 → 로컬/스테이징 부하 및 curl 인수 테스트 순서로 진행한다.

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
