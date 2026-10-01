# handoff.md

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
