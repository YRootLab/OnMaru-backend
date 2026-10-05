# Changelog

## [0.3.41](https://github.com/YRootLab/OnMaru-backend/compare/v0.3.40...v0.3.41) (2026-10-05)


### Performance Improvements

* **cd:** apply verified Gradle profile ([fa990fb](https://github.com/YRootLab/OnMaru-backend/commit/fa990fb89fccf9726b8336436ca5d3b364d96004))
* **cd:** apply verified Gradle profile to delivery builds ([f9f0c1c](https://github.com/YRootLab/OnMaru-backend/commit/f9f0c1c090bc0fbfd4faa99c41b51dd82b9503fc))

## [0.3.40](https://github.com/YRootLab/OnMaru-backend/compare/v0.3.39...v0.3.40) (2026-10-04)


### Bug Fixes

* **map:** 정보지도 집계의 한국어 지역명 복구 ([43b2a74](https://github.com/YRootLab/OnMaru-backend/commit/43b2a74acce733fa7d3252decee3892160a978c3))
* **map:** 정보지도 집계의 한국어 지역명 복구 ([#630](https://github.com/YRootLab/OnMaru-backend/issues/630)) ([19bc9f6](https://github.com/YRootLab/OnMaru-backend/commit/19bc9f6a6ed9dbb87cb00f84e7e6a0b46621c61a))


### Performance Improvements

* **ci:** adopt verified two-worker Gradle profile ([648d3bd](https://github.com/YRootLab/OnMaru-backend/commit/648d3bde9527c1be2cfaaf1aab1d6a20bcd5eff1))

## [0.3.39](https://github.com/YRootLab/OnMaru-backend/compare/v0.3.38...v0.3.39) (2026-10-04)


### Bug Fixes

* **ci:** Cloud 관측과 benchmark 실통합 준비 ([db2474e](https://github.com/YRootLab/OnMaru-backend/commit/db2474e46d1d95ae6f05240630b2968b782d7969))
* **ci:** Cloud 호환 Toolkit pin과 bootstrap 연동 ([#555](https://github.com/YRootLab/OnMaru-backend/issues/555) [#556](https://github.com/YRootLab/OnMaru-backend/issues/556)) ([bb83e76](https://github.com/YRootLab/OnMaru-backend/commit/bb83e7633384fb11cbc3afaf3598f4e3a6cf839b))

## Changelog

This project uses semantic version tags from `master`. Release notes should be generated from Conventional Commits through Release Please once releasable backend changes exist.

## Unreleased

- Issue #647의 온디맨드 스테이징 DB에 지도·장소 상세·후기·Odii 연결을 함께 검증하는 결정적 합성 fixture와 반복 seed 통합 검증을 추가했다.
- Issue #638에서 검증된 workers 2 / Gradle build cache disabled profile을 Spring Docker build와 deploy/release migration rehearsal에 적용하고 dependency·BuildKit cache는 유지했다.
- Issues #525와 #556의 실제 3+3 CI benchmark에서 2 workers/no-cache profile이 4 workers/cache baseline보다 중앙값 기준 약 23.35% 짧고 실패율 0%임을 검증해 shared Java CI 설정에 반영했다.
- Issue #543의 실제 v0.3.38/v0.3.39 release module 3+3 증적을 Release asset으로 보존하고, 중앙값 +16.108%를 15% 초과 회귀인 `approval_hold`로 검증했다.
- Issue #617의 카카오 탈퇴 회원 재로그인을 새 회원 가입으로 처리하고, 이전 기록·세션 분리와 유효한 관리자 제재 우회를 방지했다.
- Issue #586의 Lightsail Spring 운영 배포에 build 전 no-op preflight, 제한된 Blue-Green rollback과 재배포 hold, webhook 알림, Docker image 정리 및 GitHub Action SHA pinning을 추가했다.
- Issue #604의 지도 원거리 집계를 행정경계 필수 JOIN에서 장소 projection 행정코드 기반으로 전환해 빈 DISTRICT/REGION 응답을 복구하고, 중복 전체 count 쿼리를 제거해 줌아웃 timeout 위험을 낮췄다. level 6의 단일 장소 cell은 category PLACE marker로 반환해 기존 혼합 marker/cluster 계약도 복구했다.
- Issue #552의 카카오 비의존 익명 회원 프로필, 마이페이지 부분 수정 API, 온기 후기 최신 작성자 프로필과 FE 자산 ID 계약을 추가했다.
- Issue #573의 관리자 cursor 목록 응답에 필터 기준 `totalCount`를 추가하고, 서명 cursor에서 최초 전체 건수를 유지하며 PostgreSQL count query index를 보강했다.
- Issue #592의 운영 관리자 로그인 세션이 PostgreSQL JDBC의 `Instant` 타입 추론 오류로 저장되지 않던 문제를 수정하고, 생성·회전·폐기 통합 회귀 테스트를 추가했다.
- Issues #543, #554, #555, #556, #568의 신뢰된 CI 관측 후처리, 로컬 Grafana 왕복 검증, release 3회 비교, 수동 3+3 pipeline 실험과 dry-run 기본 Skill을 추가했다.
- Issue #572의 공개 cursor/limit 목록 응답에 현재 필터 기준 `totalCount`를 추가하고 OpenAPI·fixture·FE 연동 문서를 일치시켰다.
- Issue #576의 Jackson Core DoS 취약점 2건을 수정한 2.21.7·3.1.7 버전을 Spring 런타임에 고정했다.
- Issue #571의 온디맨드 스테이징 CORS allowlist에 로컬 프런트엔드 `localhost:3000`~`3008`을 추가했다.
- Issue #382의 Odii scheduler 실행을 시작·skip·실패·완료 상태와 단계별 집계로 영속화하고, 안전한 lifecycle 로그와 운영 조회 runbook을 보강했다.
- Issue #486의 한옥 목록 노출 조건과 상세 조회 조건을 통일해 목록의 `placeId`가 상세에서 404가 되지 않도록 수정했다.
- Issues #518, #520의 2026년 10월 회원 quota와 테스트 계정 예외, Journey 전용 429 응답, Gemini narration SSE 중계를 추가했다.
- Issue #566의 지도 정보모드 cursor 두 번째 page 오류를 수정하고, 목록·viewport에 네 원천 category를 묶는 `HANOK` 통합 조회 계약을 추가했다.
- Issue #561의 온기 히트맵 좌표를 지역 ID 또는 시도·시군구 주소로 정확히 매칭하고, 활성 장소 중심 좌표를 한 번씩 집계해 조회 지연을 줄였다.
- Issue #553의 기존 활성 TourAPI 지도 projection을 이관 DB에서도 복구하고, 지역 코드 누락 장소가 publication을 막지 않도록 수정했다.
- Issue #545의 검증된 `develop` 이미지 digest를 잠든 Lightsail 스테이징에 수동 반영하는 CI job과 제한 SSH 배포 경로를 추가했다.
- Issue #545의 기존 Lightsail 온디맨드 스테이징 Spring·PostGIS와 FE 전용 시작·중지 명령, 합성 데이터, 자동 중지 및 수동 smoke 경로를 준비했다.
- Issue #509의 관리자 후기·신고·회원·큐레이션 목록과 운영 검수 대기열을 서명 cursor 기반 SQL 페이지 조회로 전환하고, 대시보드 집계 read model과 FE 계약을 보강했다.
- Issue #519의 Lightsail 수동 Compose 배포 scaffold와 Render→Lightsail 전환·DB 복원·HTTPS·rollback runbook을 추가하고, Spring CORS/CSRF allowlist를 환경변수로 구성할 수 있게 했다.
- Issue #487의 cross-site 로그인 쿠키를 `SameSite=None`으로 전환하고 Vercel 및 localhost:3000~3008 CORS 허용 origin을 추가했다.
- Issue #477의 외부 저장소 비의존 공개 헬스체크를 추가하고 Swagger에서 직접 호출할 수 있게 했다.
- Issue #265의 카카오 OAuth callback이 성공·실패 후 검증된 내부 경로를 OnMaru 프론트엔드 origin에 결합해 복귀하도록 수정했다.
- Issue #463의 한옥 목록을 한 번에 최대 500건까지 조회하고, 각 카드에 FE 호환 `lat`/`lng` 좌표를 제공한다.
- Issue #453의 revision 보존 정책을 활성 PUBLISHED 1벌로 축소하고, 동일 TourAPI·ODII snapshot 재사용, TourAPI 실패 원문 미보관, 실패 STAGING 즉시 제거, 72시간 최소 동기화 간격을 적용했다.
- Issue #444의 한옥 목록 필터·주소/좌표 계약, DataLab 35일 제공 지연 대응, 전국 285개 행정구역 registry와 DB 기반 원형 히트맵을 추가했다.
- Issue #375의 TourAPI 국문 v4.4 전체 원천 적재, 공식 분류체계 기반 공개 필터, Neon 지도·한옥 snapshot과 TourAPI·Odii 03:00 KST 전체 동기화를 추가했다.
- Issue #262의 로그인 회원용 위치 기반 한옥 수결첩, PostGIS 체크인, 관계형 수결 지급, OpenAPI와 FE 연동 문서를 추가했다.
- Issue #262의 명시적 참여형 익명 수결 랭킹, 참여·철회 API, OpenAPI 1.3과 FE 연동 안내를 추가했다.
- Issue #262의 production OAuth 회원 원장을 JDBC로 연결하고, 탈퇴 시 수결 체크인·획득·랭킹 및 체크인 멱등성 응답 개인정보 삭제와 재생성 차단을 보강했다.
- Issue #307의 production Odii 저장소 선택 경쟁을 제거하고, JDBC store 필수 구성·fail-fast와 설정 순서 회귀 테스트를 추가했다.
- Issue #399의 검증 가능한 DataLab 지역 registry, fail-closed 수집, skip/quarantine metric과 staging smoke 자동화를 추가했다.
- Issue #307의 production Odii 저장소 선택 경쟁을 제거하고, JDBC store 필수 구성·fail-fast와 설정 순서 회귀 테스트를 추가했다.
- Issues #342, #343, #344, #346의 FE 호환 API, 온기 후기 필드, 스크린 한옥 JDBC 저장, 방문자 수 조회를 구현했다.
- VisitReview·신고·멱등성·DataLab 관측의 PostgreSQL 영속화와 원자적 revision 게시, 공식 지역 코드 매핑을 추가했다.
- Issue #228의 Journey 탐색 thread 기억 보존·LLM enrichment 계약 설계 및 FE 전달 문서를 추가했다.
- Issue #125의 TTL cleanup, revision GC, 회원 탈퇴 saved-data 정리, replay-safe deletion ledger와 late saved write 차단을 추가했다.
- Issue #124의 Saved journey 생성·목록·상세·재개·삭제 API, owner-scoped 404, cursor 목록, unavailableRefs 재개 응답과 구조화 로그를 추가했다.
- Issue #121의 Journey run cancel, 20초 deadline, lease expiry sweeper, durable REST cancel bridge와 재시작 orphan 회수 검증을 추가했다.
- Issue #119의 Optional RAG activation gate, corpus revision pinned rollback/activation 기록, fail-closed retrieval 연결을 추가했다.
- Issue #216의 얇은 Gradle convention plugin과 Spring module dependency direction 문서를 추가했다.
- Issue #117의 guest/member Journey AI KST 일일 quota, active admission, 영속 audit, 429 Retry-After, cancel slot 반환과 경쟁 검증을 추가했다.
- Issue #116의 Journey run SSE stage/terminal/heartbeat/replay/reset, auth close와 원인별 telemetry를 추가했다.
- Issue #115의 Exploration/run snapshot 복구 조회, current public board hydration, unavailableRefs와 run invariant 계약 검증을 추가했다.
- Issue #140의 지도·후기·Odii·찜 runtime/OpenAPI/fixture 통합 E2E 출시 gate를 추가했다.
- Issue #106의 revision-pinned corpus manifest/document export, FastAPI pull/ACK idempotency, hash mismatch·out-of-order rollback 방지를 추가했다.
- Issue #109의 PostgreSQL 기반 run 상태 머신, CAS terminal 전이, durable command replay와 active run 제약을 추가했다.
- Issue #113의 회원 전용 Odii story 저장·삭제와 type별 current-public saved-resource 목록, actor-bound signed cursor를 추가했다.
- Issue #101의 guest/member Exploration 생성·조회·turn intake, 소유권 은닉과 지역 clarification fast-path를 추가했다.
- Issue #103의 active Odii story 목록·상세 조회, 언어 fallback, 자막 provenance, 안전한 media URL 정책, 원자 published revision snapshot과 승인된 canonical place 연결을 추가했다.
- Issue #111의 deterministic AI held-out 평가, 품질·근거·안전·p95·비용 gate와 비교 가능한 report schema를 추가했다.
- Issue #94의 암호화 PostgreSQL logical backup, 격리 복원, deletion ledger replay, RPO/RTO·무결성 evidence 자동화를 추가한다.
- Issue #105의 AI proposal closed schema, candidate·pin·exclude·evidence allowlist와 typed rejection을 추가했다.
- Issue #104의 Odii–canonical place 후보 검수 상태, 단일 승인 projection, 공개 장소 hydration을 추가한다.
- Issue #134의 Journey actions·SavedJourney OpenAPI와 stateVersion·idempotency·재개 fixture 검증을 추가했다.
- Issue #91의 Gemini structured-output adapter, timeout·cancel·typed failure와 sanitized usage/cost 계측을 추가했다.
- Issue #139의 보호된 moderation queue, rotation token과 actor 기반 operator 인증, SLA projection, synthetic report/hide/restore/remove drill과 운영 runbook을 추가한다.
- Issue #96의 Odii provider client/parser, 다국어 audio revision mapping, 원자 LKG 게시와 tombstone 검증을 추가한다.
- Issue #97의 revision-pinned deterministic baseline ranking, hard filter, pin·diversity와 held-out fixture 검증을 추가했다.
- Add Git Flow branch issue parser, AGENTS release/harness policy updates, and branch-number tests for Issue #192.
- Add Spring/FastAPI multi-stage Dockerfiles, staging deploy workflow, image scan gate, and rollback release plan for Issue #82.
- Add Grafana Cloud dashboard, alert rule, notification policy exports, and staging synthetic alert checklist for Issue #81.
- Add Spring and FastAPI observability correlation boundaries with OpenTelemetry dependencies, redaction tests, and FastAPI lint/type CI gates for Issue #71.
- Add TourAPI HTTP client, URI builder, envelope parser, Spring configuration binding, and provider contract tests for Issue #73.
- Add ArchUnit module boundary checks for Spring core framework isolation, consumer-owned ports, app bridge API boundaries, and module cycle fixtures for Issue #68.
- Add Issue #69 contract validator tests for OpenAPI lint, JSON fixture/schema mismatch, DBML compile, and generated artifact drift checks.
- Add identity/member/SavedResource OpenAPI fixtures and contract validation for Issue #132.
- Add R2 map, region, Odii, and tourism insights OpenAPI fixtures with R1/R2 contract validation.
- Add shared Spring web contracts for schemaVersion 1.2 error envelopes, request IDs, signed cursors, and idempotency primitives for Issue #70.
- Add D01 Flyway baseline migration, migration registry policy checks, and PostgreSQL role separation tests for Issue #67.
- Add server-only secret loading, rotation overlap, and log redaction wiring for Spring and FastAPI runtimes.
- Add R1 hanok/place/saved-resource OpenAPI fixtures and CI contract validation for OpenAPI, JSON Schema, DBML, generated SQL, Spring, and FastAPI checks.
- Harden Odii fixture validation to reject long URL-encoded token query values in captured manifest URLs.
- Capture redacted Odii API qualification fixtures and license/provenance manifest for Issue #61.
- Add TourAPI qualification manifest, redacted provider fixtures, and CI fixture validation for Issue #66.
- Add reproducible backend planning-input snapshots with provenance manifest, hash verification, and secret scanning in CI.
- Add PostgreSQL/PostGIS Testcontainers smoke coverage, clean test DB reset helper, and local compose readiness checks.
- Add reproducible Java 21/Spring Boot and Python 3.12/FastAPI development scaffolds with locked dependencies, health checks, and offline-cache verification.
- Standardize the Java namespace and Gradle group on `com.yrootlab.onmaru` across source, planning graphs, and published Issue paths.
- Document post-audit backend planning refinements, DBML ERD modules, and Azimutt PNG handoff workflow.
- Prepare backend implementation issue graph, P0 triage, and runtime ADR follow-up after PR #48.
- Initialize project operating harness, Git Flow policy, release automation baseline, and ADR directory.
