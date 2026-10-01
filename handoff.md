# handoff.md

## 현재 작업: Lightsail 배포 릴리스 준비 (#519, #526)

- 기준일: 2026-10-01
- 브랜치: `feature/526-release-sync` (`release/v0.3.35`에서 분기)
- PR #523은 `develop`에 merge됐고 커밋 `4a3c1c4`의 CI `verify`가 통과했다. #519는 실제 AWS 배포 전까지 open으로 유지한다.
- 일반 PR의 Module Benchmark 자동 실행 문제 #524는 PR #523 병합으로 종료됐다. Java CI 실행 구조 개선 #525는 배포 이후 검토한다.
- 다음 릴리스는 #526에서 `develop → release/v0.3.35 → master` 순서로 검증한다. 현재 최신 Git 태그가 `v0.3.34`인 반면 `master`에는 v0.3.35 변경이 이미 병합되어 있어 버전/태그 정합성을 확인해야 한다.

### 서버 준비 상태

- Lightsail `onmaru-prod-seoul` (서울, Ubuntu 24.04, 1 GB RAM), 고정 IPv4 `13.125.191.16`, SSH 사용자 `ubuntu`.
- 서울 리전 기본 SSH 키를 로컬 `~/.ssh/onmaru-lightsail-seoul.pem`에 보관하고 권한 `600`을 적용했다. 해당 키로 SSH 접속을 확인했다. 키 본문은 저장소와 문서에 기록하지 않는다.
- Docker 29.8.2와 Compose v5.5.1이 설치됐고 Docker 서비스는 active다. `/opt`에 약 35 GB 여유가 있고 swap은 없다.
- `/opt/onmaru/repo`에 `develop` 커밋 `4a3c1c4`를 clone했다. Lightsail Compose 설정은 서버에서 `.env.example`로 parse 검증을 통과했다.
- 서버에는 실제 `.env`, DB dump, 컨테이너, Nginx 인증서가 아직 없다. DNS `api.onmaru.site` A record도 아직 없다. Render API/DB와 FE Production은 그대로 유지한다.

### 다음 단계

1. `feature/526-release-sync`에서 `origin/develop`을 병합했고 `handoff.md` 충돌을 현재 배포 상태로 갱신해 해결했다. forward sync PR을 `release/v0.3.35`에 열고 CI를 확인한다.
2. 릴리스 버전 정책은 최신 태그 `v0.3.34`에 대한 후보 `v0.3.35`로 통과했다. staging 검증 후 `master` 승격 PR을 준비한다.
3. `master`에서 새 GHCR Spring 이미지 SHA/digest가 생성되면 서버 repo를 해당 commit으로 맞추고 배포한다.
4. 서버 `.env`의 실제 비밀값과 Render DB dump/restore는 안전한 경로로 준비한다. Spring은 DB 복원과 검증 전에 시작하지 않는다.
5. DNS/TLS, API health, FE Preview의 CORS/CSRF·Kakao·세션·SSE를 검증한 뒤 Production API base URL 전환을 판단한다.

## 열린 위험

- 1 GB RAM 단일 인스턴스라 Spring/PostgreSQL/Nginx 실행 시 메모리 부족 가능성이 있다.
- SSH 22는 아직 전체 IPv4/IPv6에 열려 있어 로컬 SSH 확인 후 관리자 주소로 제한해야 한다.
- Render DB 버전·용량, backup/restore, GHCR 접근, 운영 secret, 별도 backup 보존 위치는 아직 확인되지 않았다.
- 실제 운영 전환 전에는 Render 자원을 삭제하거나 양쪽 DB를 동시에 활성 writer로 사용하지 않는다.
