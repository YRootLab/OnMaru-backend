# Changelog

## [0.4.0](https://github.com/YRootLab/OnMaru-backend/compare/v0.3.13...v0.4.0) (2026-09-26)


### Features

* **audio:** broaden Odii curated synchronization ([551b8fa](https://github.com/YRootLab/OnMaru-backend/commit/551b8fa4bb7c4f68171fd7b002ffee8502aaa17b))
* **audio:** Odii 전체 스토리 수집 및 제외 정책 적용 ([#362](https://github.com/YRootLab/OnMaru-backend/issues/362)) ([2843c8c](https://github.com/YRootLab/OnMaru-backend/commit/2843c8c2dd86e5f4e105f05446a5f2e4886a7f6d))
* **audio:** 광역 지역 그룹별 오디오 스토리 카운트 조회 API 구현 ([04333f6](https://github.com/YRootLab/OnMaru-backend/commit/04333f657d081404bf55c4d59b2986d2967dbe72))
* **audio:** 광역 지역 그룹별 오디오 스토리 카운트 조회 API 구현 ([#306](https://github.com/YRootLab/OnMaru-backend/issues/306)) ([2d1f649](https://github.com/YRootLab/OnMaru-backend/commit/2d1f649000de281dbd6f9257547d951a1adaf86b))
* **audio:** 소리마루 Single Source of Truth API 구현 ([942e3d0](https://github.com/YRootLab/OnMaru-backend/commit/942e3d0c1d20a376a2924a5e4435f8d782ec8690))
* **audio:** 소리마루 검색 근처 추천 API 추가 ([679b1e9](https://github.com/YRootLab/OnMaru-backend/commit/679b1e90bcab9391d48ac82e9700f36f02fedfc3))
* **benchmark:** [#336](https://github.com/YRootLab/OnMaru-backend/issues/336) release metadata와 evidence manifest 구현 ([e0954ee](https://github.com/YRootLab/OnMaru-backend/commit/e0954ee8eb5518572ca85b04f3830dc5842a2088))
* **benchmark:** [#338](https://github.com/YRootLab/OnMaru-backend/issues/338) release benchmark workflow 통합 ([d580db6](https://github.com/YRootLab/OnMaru-backend/commit/d580db6209967da137405821f1d2a2f179cad999))
* **benchmark:** [#338](https://github.com/YRootLab/OnMaru-backend/issues/338) release benchmark workflow 통합 ([e22d28a](https://github.com/YRootLab/OnMaru-backend/commit/e22d28ae77423e6aa39bf669aea59a70b8076206))
* **benchmark:** add serial verify baseline manifest ([#371](https://github.com/YRootLab/OnMaru-backend/issues/371)) ([afe6349](https://github.com/YRootLab/OnMaru-backend/commit/afe6349462e5fd224fb63f70c5f8e4b7a3294628))
* **benchmark:** collect CI baseline evidence ([#389](https://github.com/YRootLab/OnMaru-backend/issues/389)) ([6a6b1de](https://github.com/YRootLab/OnMaru-backend/commit/6a6b1de6ad3aac1a93f59994d70fa5ee45e988be))
* **benchmark:** implement release metadata and evidence manifest ([43891a9](https://github.com/YRootLab/OnMaru-backend/commit/43891a94cbb5c9acc0da2e85c51fb3de877a366d))
* **benchmark:** pipeline-toolkit adapter 구현 ([32cde18](https://github.com/YRootLab/OnMaru-backend/commit/32cde1846e4b617e9a67cfc885fed80eab62d671))
* **benchmark:** pipeline-toolkit adapter 구현 ([b6f5cbb](https://github.com/YRootLab/OnMaru-backend/commit/b6f5cbbcf0a3b455d7958b19ef9ae51a21af1601))
* **ci:** Toolkit module benchmark shadow caller 추가 ([b74fe87](https://github.com/YRootLab/OnMaru-backend/commit/b74fe8753d79e89ca5ec2f344ef8091d24cab0e8))
* **ci:** Toolkit module benchmark shadow caller 추가 ([7ced735](https://github.com/YRootLab/OnMaru-backend/commit/7ced735d62763c2777de03f33422d44b8f3fd19f))
* **release:** connect trend evidence to toolkit ([#387](https://github.com/YRootLab/OnMaru-backend/issues/387)) ([3ee5122](https://github.com/YRootLab/OnMaru-backend/commit/3ee51228b82df3db3f2d73dd6bcad3567e152264))
* **saved,audio:** 찜 데이터 Neon DB 영속화(V017)와 이번 주 인기 한옥 소리 TOP N API ([42f928c](https://github.com/YRootLab/OnMaru-backend/commit/42f928c036a409a3ab739b9ef9f34d73cf5f2a5a))
* **saved,audio:** 찜 영속화(V017)와 이번 주 인기 한옥 소리 TOP N API ([#318](https://github.com/YRootLab/OnMaru-backend/issues/318),[#317](https://github.com/YRootLab/OnMaru-backend/issues/317)) ([8faa65e](https://github.com/YRootLab/OnMaru-backend/commit/8faa65e26626f5f8846532b4efede4e011ee1e8e))


### Bug Fixes

* **ai:** AI 이미지 pip CVE 및 Trivy 스캔 게이트 통과를 위한 예외 처리 ([a496a37](https://github.com/YRootLab/OnMaru-backend/commit/a496a3779765e942dd179e125ccea87925dfc1fd))
* **ai:** pip 업그레이드 및 Trivy vendored-pkg 오탐 예외로 이미지 스캔 게이트 통과\n\n- ai/Dockerfile: 시스템 pip을 26.2.1로 업그레이드해 CVE-2026-8643(HIGH) 해소\n- .trivyignore 추가: pip vendored msgpack(GHSA-6v7p-g79w-8964)과 오탐 setuptools(CVE-2025-47273) 근거 문서화\n- deploy.yml AI 이미지 스캔에 trivyignores 연결\n- 로컬 검증: docker build + trivy CRITICAL,HIGH --exit-code 1 → EXIT 0\n\nCloses [#314](https://github.com/YRootLab/OnMaru-backend/issues/314) ([9eeb852](https://github.com/YRootLab/OnMaru-backend/commit/9eeb8521b9077b57262c622db1e3402a4992750b))
* **ai:** setuptools 83.0.0+ 명시 설치로 CVE-2026-59890 이미지 스캔 게이트 통과 ([de30c14](https://github.com/YRootLab/OnMaru-backend/commit/de30c14d5409d149a827a27aacb34e43a27b1573))
* **ai:** setuptools 83.0.0+ 명시 설치로 CVE-2026-59890 이미지 스캔 게이트 통과 ([78a3b8c](https://github.com/YRootLab/OnMaru-backend/commit/78a3b8ca51dd16f3ef1b8a1b69748bbef583619a))
* **audio:** initialize Odii dataset before scheduled sync ([1470b85](https://github.com/YRootLab/OnMaru-backend/commit/1470b850c04ed83efa6bd2248e53eb691bc4aab2))
* **audio:** Odii 동기화 적재 및 큐레이션 보강 ([a7611b5](https://github.com/YRootLab/OnMaru-backend/commit/a7611b53e7db2928f86c224ab93bfeb85a0e2905))
* **audio:** PR 계약과 변경 범위 정리 ([f0fa1b3](https://github.com/YRootLab/OnMaru-backend/commit/f0fa1b3bd918dd73a56c16af8b30e9437e1efbf0))
* **benchmark:** stabilize timeout fixture ([f27d048](https://github.com/YRootLab/OnMaru-backend/commit/f27d048d9634d3ec4f5893d8b79c1c112d91ae31))
* **ci:** AI 이미지 미수정 취약점으로 배포 차단 해소 ([8aba126](https://github.com/YRootLab/OnMaru-backend/commit/8aba12696c77e4c2f4a8fc4b80148398c23f1df6))
* **ci:** bootstrap uv for AI module benchmark ([#379](https://github.com/YRootLab/OnMaru-backend/issues/379)) ([f5b75f0](https://github.com/YRootLab/OnMaru-backend/commit/f5b75f0dff536358890344383bfd33791ac99607))
* **ci:** ignore unfixed AI image vulnerabilities ([65523b9](https://github.com/YRootLab/OnMaru-backend/commit/65523b93b80c543edbc69ae4c98e17a93af79b8e))
* **ci:** make staging scan and rollback gates deterministic ([27f8775](https://github.com/YRootLab/OnMaru-backend/commit/27f8775df746bf346e8e5270e9f8b1b53214f2ed))
* **ci:** Toolkit v0.1.2 SHA로 caller 갱신 ([c687b40](https://github.com/YRootLab/OnMaru-backend/commit/c687b4033bab1ae3509f10ad17e41cbfd1cb498c))
* **cors:** allow Render frontend Odii requests ([4ee0ff7](https://github.com/YRootLab/OnMaru-backend/commit/4ee0ff78907af76274fae57ec7c913dec76676d3))
* **cors:** Render 프론트 Odii API 403 차단 해소 ([cf50bb0](https://github.com/YRootLab/OnMaru-backend/commit/cf50bb0e6e18871675d35144b31ba6c72a919978))
* **db:** V017 migration registry 등록 ([ece8d5d](https://github.com/YRootLab/OnMaru-backend/commit/ece8d5d0da035f6e1d1db1c39d34f549f5e547e3))
* **deploy:** Trivy SARIF 업로드 category 중복으로 staging 배포 실패하는 문제 수정 ([e24bb56](https://github.com/YRootLab/OnMaru-backend/commit/e24bb565aa5fc31f484a314d38170f2d657aa4bc))
* **deploy:** Trivy SARIF 업로드 category 중복으로 staging 배포 실패하는 문제 수정\n\n- Spring API/ai-service SARIF 업로드에 각각 고유 category 지정\n- handoff.md에 릴리즈 v0.3.13 세션 결과 기록\n\nCloses [#310](https://github.com/YRootLab/OnMaru-backend/issues/310) ([e69095f](https://github.com/YRootLab/OnMaru-backend/commit/e69095fd7eafa4b3f055c69b7fe2ff353a6a938a))
* **test:** V017 마이그레이션 계약 버전 갱신과 editorial 테스트 컨텍스트 격리 ([4b86a11](https://github.com/YRootLab/OnMaru-backend/commit/4b86a11ace5d68f22ef295da5f49be4983c9d953))

## Changelog

This project uses semantic version tags from `main`. Release notes should be generated from Conventional Commits through Release Please once releasable backend changes exist.

## Unreleased

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
