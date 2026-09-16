# Changelog

This project uses semantic version tags from `main`. Release notes should be generated from Conventional Commits through Release Please once releasable backend changes exist.

## Unreleased

- Issue #140의 지도·후기·Odii·찜 runtime/OpenAPI/fixture 통합 E2E 출시 gate를 추가했다.
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
