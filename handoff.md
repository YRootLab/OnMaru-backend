# handoff.md

## 현재 작업: Lightsail 배포 준비 (#519)

- 기준일: 2026-10-01
- 브랜치: `feature/519-lightsail-deployment` (branch parser 결과: #519)
- Git Flow: 기능 변경은 `develop` PR로 반영하고, 실제 운영 배포는 release 흐름을 거친 `master` 이미지 기준으로 한다. 이 PR은 배포 문서와 수동 배포 scaffold를 제공하며 #519를 닫지 않는다.
- 현재 GitHub 상태: PR 생성 전. 변경 사항과 이 문서를 커밋하고 push한 뒤 `develop` 대상 PR을 연다. `verify` CI 결과를 확인한다.

### 이번 변경

- `infra/lightsail/`: Nginx/Spring/PostGIS Compose, 역할 분리 init/restore, DB backup, HTTP ACME bootstrap, HTTPS proxy, Certbot renewal timer, 비밀값 없는 `.env.example`, 실행 README를 준비했다.
- `docs/operations/runbooks/lightsail-render-cutover.md`: 현재 AWS 상태부터 DB 복원, HTTPS, FE 검증, 전환·rollback 및 Render 종료 조건까지 기록했다.
- Spring CORS를 환경변수 allowlist로 바꾸고 `/auth/csrf`에도 적용했다. CSRF 필터가 같은 Origin 정책을 사용하며 forwarded-header 처리 설정을 추가했다.
- `Dockerfile`의 JVM 메모리 제한을 컨테이너 메모리 기준 환경변수로 전달하도록 조정했다.
- 로컬 `.agents/`, `.claude/`, `skills-lock.json`은 기존 미추적 작업물이며 이번 PR에 포함하지 않는다.

### 검증 기록

- 이미 수행된 로컬 검증: Lightsail Compose config parse, Spring `:apps:spring-api:compileJava`, 셸 스크립트 syntax, YAML parse, `git diff --check` 통과. 자동 테스트는 실행하지 않았다.
- 실제 Lightsail Nginx/TLS, Render DB 복원, FE 로그인·쿠키·CORS, 운영 트래픽 전환은 아직 검증하지 않았다.

### AWS 진행 상태

- 인스턴스: `onmaru-prod-seoul`, Seoul `ap-northeast-2a`, Ubuntu 24.04, $7/월 번들(1 GB RAM, 2 vCPU, 40 GB SSD).
- 고정 IP 리소스: `onmaru-prod-seoul-ip` 연결 완료. 숫자 IP는 사용자가 콘솔에서 확인해야 하며 아직 DNS에 등록하지 않았다.
- 방화벽: TCP 80 공개, TCP 443 공개, TCP 22는 전체 IPv4/IPv6 및 Lightsail 브라우저 SSH로 열려 있다. 22 제한은 별도 로컬 SSH 경로를 확인한 뒤 production 전에 적용한다. 8080/5432는 열지 않는다.
- 인스턴스 패키지 업데이트 후 재부팅했다. 사용자가 브라우저 SSH 재접속, Docker/Compose 설치 및 Docker 서비스 `active` 확인, `/opt/onmaru` 생성과 `ubuntu` 소유권 설정을 완료했다.
- Docker 설치는 확인됐지만 `hello-world` 컨테이너 성공 여부는 아직 보고되지 않았다. 저장소/배포 파일, `.env`, 컨테이너, PostgreSQL 데이터, Nginx/TLS는 서버에 아직 없다.
- Automatic Snapshots는 비용을 고려해 꺼져 있다. PostgreSQL 별도 backup/restore 절차는 필수다.

### 다음 작업과 순서

1. 이번 변경만 stage하고 Conventional Commit으로 커밋한다. 로컬 skill 파일은 제외한다.
2. feature 브랜치를 origin에 push하고 #519 참조 `develop` PR을 생성한다 (`Closes #519` 사용 금지).
3. 필수 `verify` CI와 PR diff를 확인한다. merge 전에 사용자와 검토한다.
4. PR 반영 뒤 GitHub workflow에서 사용할 이미지 SHA와 GHCR 접근 방식을 확인한다.
5. 서버에 배포 파일을 가져와 비밀값을 `.env`에 설정하고 `chmod 600`을 적용한다.
6. PostgreSQL/PostGIS만 먼저 시작하고 Render 원본 dump를 안전하게 복원·검증한다. 그 전에는 Spring을 시작하지 않는다.
7. Spring/Nginx를 구성하고 HTTP challenge로 TLS를 발급한 다음 HTTPS, health, CORS/CSRF, Kakao, SSE를 확인한다.
8. 그 뒤 `api.onmaru.site` DNS 및 FE Preview를 검증한다. 운영 전환 전까지 Render API/DB, Vercel `@`/`www`, Render Kakao callback을 유지한다.
9. 실제 운영 전환은 `develop` 직접 배포가 아니라 승인된 release 절차를 거친 `master` 이미지로 수행한다. DB 백업/복구 및 rollback 조건 확인 전 Render 자원을 종료하지 않는다.

## 남은 운영 위험

- Lightsail 한 대에 API와 DB가 함께 있어 호스트 장애 시 둘 다 중단되는 단일 장애 지점이다. 현재는 저비용 테스트/초기 운영 목표다.
- SSH 22 인바운드가 넓게 열려 있다. 접속 경로를 보존하며 source IP를 제한해야 한다.
- $7 번들은 1 GB RAM이라 Spring, PostgreSQL/PostGIS, Nginx 메모리 사용량을 관찰하고 OOM이 반복되면 상향을 검토한다.
- Render 데이터의 실제 DB 버전/용량과 dump/restore 결과, GHCR package visibility, 필요한 운영 secret은 아직 확인하지 않았다.
