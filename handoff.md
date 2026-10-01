# handoff.md

## 현재 작업: Lightsail 배포 릴리스 준비 (#519, #526)
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
