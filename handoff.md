# handoff.md

## Current Session Quick Handoff - 2026-09-14 Issue #133

- 현재 작업 브랜치와 worktree: `SHcommit/m06-odii-r2-openapi-fixture`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/m06-odii-r2-openapi-fixture`.
- Issue #133 `[M06] 지도·Odii·관광 관측 R2 OpenAPI·fixture 동결` 작업을 진행했다.
- 선행 조건 확인: #131, #62는 Closed이며, `gh pr list --search "133"` 기준 열린 중복 PR은 없었다.
- TDD 기록: `python3 scripts/test/validate-r2-contract.py`를 먼저 추가했고, RED는 `docs/contracts/openapi/r2-map-audio-insights.openapi.yaml` 누락으로 실패했다.
- 산출물: `docs/contracts/openapi/r2-map-audio-insights.openapi.yaml`, `docs/contracts/fixtures/r2/*.json` 13개, `scripts/test/validate-r2-contract.py`, `scripts/verify-contracts` R2 검증 연결, `scripts/test/requirements-contract.txt` 공통 계약 검증 의존성 파일, `docs/contracts/README.md` 링크.
- 범위: runtime endpoint 구현은 제외하고, R2 public read endpoint·결측·언어·coverage status 계약과 fixture만 동결했다.
- 검증 통과: `test -f scripts/test/requirements-contract.txt && ! rg -q 'requirements-r1-contract\\.txt' .github/workflows/ci.yml`, `python3 scripts/test/validate-r1-contract.py`, `python3 scripts/test/validate-r2-contract.py`, `./scripts/verify-contracts`.

## Current Session Quick Handoff - 2026-09-14 Issue #61 Redaction Hardening

- 현재 작업 브랜치와 worktree: `SHcommit/a01-odii-api-license-qualification`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/a01-odii-api-license-qualification`.
- 사용자 요청: Issue #61을 `agent-toolkit-skills:backend-developer`와 TDD로 진행한다.
- 기존 상태: PR #145 `docs(api): Odii 실제 응답 fixture 고정`은 2026-09-14에 `develop`으로 merge됐고 본문에 `Closes #61`가 있었지만, GitHub Issue #61은 아직 Open이다.
- 이번 세션 보강: `scripts/test/odii-fixture-validation.test.mjs`에 긴 URL-encoded token query 값이 manifest URL에 남으면 실패하는 RED 테스트를 추가했고, `scripts/lib/odii-fixture-validation.mjs`가 URL query 값을 검사해 secret-like token을 차단하도록 구현했다.
- 검증 통과: `node --test scripts/test/odii-fixture-validation.test.mjs`, `node scripts/validate-odii-fixtures.mjs`, `node --test scripts/test/*.test.mjs`, `./gradlew test`, `cd ai && uv run pytest`, `git diff --check`.
- PR 생성 시 `Refs #61`로 연결한다. Issue #61은 PR #145 merge와 이번 redaction hardening PR merge, Acceptance Criteria 재확인 후 수동 close 후보로 둔다.

## Current Session Quick Handoff - 2026-09-14 Issue #65

- 현재 작업 브랜치와 worktree: `feature/65-f04-postgis-testcontainers`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/develop`.
- 사용자 요청: `Refs: #65` 구현을 진행하고, PR merge 시 해당 Issue를 완료 처리해 닫는다. PR 본문에는 acceptance 충족 근거와 `Closes #65`를 반영한다.
- 구현 범위: Spring 테스트용 Testcontainers core + PostgreSQL JDBC 의존성을 추가하고, `postgis/postgis:17-3.5-alpine` 컨테이너 smoke test로 `PostGIS_Version()`과 반복 reset 후 clean DB 보장을 검증한다.
- 로컬 인프라: `infra/local/postgres/compose.yaml`에 PostGIS 포함 PostgreSQL과 `pg_isready` + `pg_extension` readiness healthcheck를 추가했다. 기본 포트는 5432이며 `ONMARU_POSTGRES_PORT`로 변경 가능하다.
- 테스트 reset helper: `PostgresTestDatabase.reset(Connection)`은 `public` schema를 drop/create하고 `postgis` extension을 다시 보장한다.
- 검증: `./gradlew :apps:spring-api:test`, `docker compose -f infra/local/postgres/compose.yaml config --quiet`, `ONMARU_POSTGRES_PORT=55432 docker compose -p onmaru_issue65 -f infra/local/postgres/compose.yaml up -d --wait`, `git diff --check`, CI filesystem baseline, `uv run pytest` from `ai/`, `node --test scripts/test/*.test.mjs` 통과. 검증용 compose 리소스는 `down -v`로 제거했다.
- 후속 연결: CI 확장은 기존 Issue #69 범위로 유지한다. #65 PR merge 전 review/CI 상태와 acceptance criteria를 다시 확인한다.

## Current Session Quick Handoff - 2026-09-14 Issue #62

- 현재 작업 브랜치와 worktree: `feature/62-c01-place-openapi-fixture`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/62-c01`.
- Issue #62 `[C01] R1 한옥·장소·찜 OpenAPI와 fixture 동결` 작업을 진행했다. PR에서 실제 완료할 범위이므로 본문에는 `Closes #62`를 사용한다.
- 산출물: `docs/contracts/openapi/r1.openapi.yaml`, `docs/contracts/fixtures/r1/*.json` 13개, `scripts/test/validate-r1-contract.py`, `scripts/test/requirements-r1-contract.txt`, `.github/workflows/ci.yml`의 R1 contract validation 및 FastAPI service tests 단계.
- 추가 CI 보강: `.github/workflows/ci.yml`에 JDK 21 Gradle cache 기반 `./gradlew test --no-daemon`을 추가했다. `scripts/verify-contracts`는 R1 OpenAPI/fixture 검증, DBML compile, Azimutt/Postgres 생성물 stale 검증을 수행한다.
- 최신 `origin/develop` merge 후 Node script tests, planning input snapshot validation, Odii fixture manifest validation과도 CI workflow를 병합했다.
- `scripts/azimutt-export.mjs`는 `AZIMUTT_OUTPUT_DIR` 환경 변수를 지원해 CI/로컬 검증에서 임시 디렉터리에 생성물을 만들 수 있다. 이 덕분에 uncommitted working tree에서도 generated artifact diff를 검증할 수 있다.
- #69는 blocked-by인 #63/#64가 GitHub 상 Open 상태라 이번 PR에서 자동 종료하지 말고 `Refs #69`로 연결하는 편이 안전하다.
- #62 범위에 맞춰 endpoint code는 추가하지 않았다. public schema와 fixture에는 `contentId`, `pageNo`, provider `key`, `serviceKey`를 노출하지 않는다.
- 한옥 상세, 지도 카드, Odii 연결 장소 카드는 fixture에서 같은 canonical `placeId`(`p-jeonju-hanok-village`)를 공유하도록 검증한다.
- 로컬 검증 `bash scripts/verify-contracts`, `./gradlew test`, `cd ai && uv run pytest`, `git diff --check` 통과. 최종 merge 전 PR CI와 review 상태를 다시 확인한다.

## Current Session Quick Handoff - 2026-09-14

- 현재 작업 브랜치와 worktree: `chore/61-a01-odii-api-validation-2`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/develop-2`.
- Issue #61 A01 Odii qualification 범위로 실제 Odii API 응답 fixture 8개를 `testing/fixtures/provider/odii`에 redacted 저장했고, `docs/reference-snapshots/odii/manifest.json`과 `README.md`에 hash, license/quota, field mapping, official transcript/empty script/audioUrl 정책을 기록했다.
- 추가한 검증: `scripts/capture-odii-fixtures.mjs`, `scripts/validate-odii-fixtures.mjs`, `scripts/lib/odii-fixture-validation.mjs`, `scripts/test/odii-fixture-validation.test.mjs`. fixture/manifest는 `serviceKey`를 제거하고 secret-like 문자열과 hash drift를 검증한다.
- 검증 통과: `node --test scripts/test/*.test.mjs`, `node scripts/validate-odii-fixtures.mjs`, `node scripts/verify-planning-inputs.mjs`, `git diff --check`, `./gradlew test`, `cd ai && uv run pytest`.
- PR은 `develop` 대상으로 생성하고 merge 시 #61을 닫을 수 있으므로 본문에 `Closes #61`을 사용한다. 후속 #96 A02는 이 fixture와 README의 provider 계약을 기준으로 구현한다.

- 직전 develop merge 포함 작업: `chore/66-p01-tour-api-validation`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/66-p01`.
- Issue #66 기반 한국관광공사 국문 TourAPI qualification manifest와 redacted provider fixture 9종을 추가했다. 산출물은 `docs/reference-snapshots/tourapi/manifest.json`, `docs/reference-snapshots/tourapi/README.md`, `testing/fixtures/provider/tourapi/*.json`이다.
- #66 fixture는 정상 목록, 좌표 기반 목록, 상세 공통 정보, 빈 목록, 마지막 page, HTTP 200 오류 envelope, 4xx 인증 오류, 5xx/GW 장애, 429 quota 초과를 포함한다. 서비스 키와 원본 query string은 저장하지 않고 `<REDACTED>`만 사용한다.
- CI에 `node --test scripts/test/*.test.mjs` documentation fixture 검증을 추가했다. 로컬 검증은 `node --test scripts/test/*.test.mjs` 통과.
- PR은 #66 완료로 닫아야 하므로 본문에 `Closes #66`를 사용한다.

- 직전 develop merge 포함 작업: `feature/131-f07-design-provenance`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/131-f07`.
- Issue #131 기반으로 backend 설계 입력을 `docs/reference-snapshots/planning-inputs`에 repo-local snapshot으로 고정했다. `docs/backend_schema_design_guide.md`와 `docs/specs`는 개인 절대경로가 아니라 snapshot을 가리키는 상대 symlink다.
- `docs/reference-snapshots/planning-inputs/manifest.json`에는 원본 repository, commit, source path, SHA-256을 기록했다. `OnMaru-docs` 원본 working tree에 로컬 변경이 있어 snapshot은 재현 가능한 HEAD blob 기준으로 생성했다.
- `scripts/verify-planning-inputs.mjs`와 `scripts/lib/planning-inputs-verifier.mjs`를 추가했다. 검증은 manifest 누락/변조, manifest 밖 snapshot 파일, snapshot·contract fixture의 secret-like 값을 실패 처리한다.
- CI는 `node --test scripts/test/*.test.mjs`와 `node scripts/verify-planning-inputs.mjs`를 실행한다.
- 검증 통과: `node --test scripts/test/*.test.mjs`, `node scripts/verify-planning-inputs.mjs`, `git diff --check`, `./gradlew --no-daemon check`, `cd ai && uv sync --frozen --all-groups && uv run ruff check . && uv run mypy src tests && uv run pytest`.
- PR은 `develop` 대상으로 생성한다. 저장소 default branch가 `main`이라 GitHub auto-close가 잡히지 않으므로 PR 본문은 `Refs #131`로 두고, `develop` merge와 Acceptance Criteria 확인 후 #131을 수동 close한다.
- 이전 setup 작업 브랜치와 worktree: `feature/setup-issues`, `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/issues-setup`.
- Issue #63 기반 Java 21, Spring Boot 4.1.1, Gradle Wrapper 9.7.1 멀티프로젝트와 `apps/spring-api` 실행 골격을 구축했다. Web MVC·Actuator 및 테스트 의존성은 lock하고 로컬 Gradle 캐시에 받았다.
- Issue #64 기반 uv 관리 Python 3.12, FastAPI, Uvicorn, pytest, HTTPX, Ruff, mypy 골격을 `ai/`에 구축했다. `uv.lock`의 33개 패키지를 `ai/.venv`와 uv 캐시에 받았다.
- Spring context/Actuator 테스트 2개와 FastAPI health/readiness 테스트 2개가 통과한다. 실제 Spring `:apps:spring-api:bootRun`의 `/actuator/health`와 Uvicorn의 `/health`, `/ready`도 HTTP 200으로 확인했다.
- 온라인 전체 검증과 같은 장비의 `./gradlew --offline` 및 `UV_OFFLINE=1` 검증이 모두 통과했다. 다른 장비에는 캐시가 자동으로 이동하지 않는다.
- IntelliJ 실행법은 `apps/spring-api/README.md`, VS Code 실행법은 `ai/README.md`, 전체 최초 설치·오프라인 명령은 루트 `README.md`에 있다.
- DB, Flyway, Security, TourAPI adapter, Gemini/RAG 라이브러리는 후속 Issue 소유이므로 이번 scaffold에 미리 추가하지 않았다.
- 사용자 승인으로 Java 기본 package와 Gradle group을 `com.yrootlab.onmaru`로 확정했다. 실행 코드·테스트·기획 graph/draft와 GitHub Issue 본문 52개의 예상 경로를 동기화했고, 재실행 dry-run에서 body/parent/blocked-by 차이 0을 확인했다.
- 사용자 요청에 따라 PR·Issue·리뷰 요약·작업 문서는 한국어를 기본으로 작성하고 Conventional Commit type, 기술 키워드, skill·library·코드 식별자는 영어를 허용하는 언어 정책을 `AGENTS.md`에 추가했다. PR #141의 제목과 본문도 이 정책에 맞춘다.
- 이 절은 아래의 과거 “runtime scaffold 없음” 기록보다 최신 상태다. #63·#64는 branch merge와 acceptance criteria 확인 후에만 닫는다.

## Next Session Quick Handoff - 2026-09-12

- 현재 작업 브랜치: `feature/setup-issues`. 새 세션 시작 시 `git status --short --branch`로 다시 확인한다.
- 임시 공개 MVP AI 정책: Gemini는 명시적 opt-in 후 비회원 KST 일 2회, 로그인 회원 KST 일 5회만 허용한다. 한도 소진·AI 실패 뒤 baseline 탐색은 계속 가능하며, 유료 전환은 실제 quota/abuse/전환 데이터를 본 뒤 별도 GitHub Issue에서 결정한다.
- Gemini timeout, quota 소진, malformed output, FastAPI 내부 장애 시 Spring은 이미 검증한 자연어 intent·후보 집합을 같은 run의 `engine=BASELINE` 결과로 완료한다. 이는 두 번째 provider 호출이 아니며, 지역/주제 해석·후보 검색 실패, cancel, 정책 거절, Spring 불변식 실패에는 적용하지 않는다. FE는 raw 장애 대신 기본 탐색 결과로 표시한다.
- 기획 확정 뒤 GitHub Issue graph에 반드시 포함할 공개 출시 작업: (1) VisitReview 작성 화면의 개인정보·금지 내용 안내, 신고 안내, 계정 삭제 경로, 개인정보 처리방침/서비스 정책 초안과 public-write 전 검증, (2) 운영 배포 도메인 확정 후 Kakao Login 운영 Redirect URI·동의항목·운영 앱 소유 계정·개발/운영 앱 분리 설정과 callback/logout/error fixture. 이는 roadmap/improvements가 아닌 독립 Issue로 발행한다.
- FE에 전달할 신규·변경 백엔드 기능은 `docs/toFE/`에 모았다. `feature-delta.md`는 기존 `docs/specs` 대비 변화, `integration-checklist.md`는 화면/fixture 완료 조건, `README.md`는 기계 계약 링크를 제공한다. API 필드의 정답은 계속 `docs/contracts/`다.
- 문화 Q&A와 한옥 문화 콘텐츠 페이지는 현재 여정 MVP와 Issue graph에서 제외한다. 원천 라이선스·corpus 검수·안전 정책을 별도 승인할 수 있을 때만 `project-roadmap.md`의 장기 옵션으로 재검토한다.
- 여정 AI는 Spring의 deterministic intake/지역·후보 검색과 FastAPI의 provider-neutral typed proposal adapter로 분리한다. 여정 진행은 SSE 알림과 GET snapshot 복구를 사용한다. FastAPI가 RAG corpus sync·embedding·retrieval·`ai` schema를 소유하고 Spring은 revision-pinned corpus export만 제공한다. Gemini tool calling, LangChain/LangGraph, 외부 URL/검색 tool은 MVP 기본 경로에서 제외하며 상세 계약은 `docs/ai/journey-guardrails.md`와 `docs/ai/application-architecture.md`에 있다.
- 관측성 기준은 Spring Actuator + Micrometer/OTel 및 FastAPI OpenTelemetry SDK에서 Grafana Cloud로 OTLP를 보내는 구성이다. Grafana Cloud dashboard/alert history가 운영 기준이고, warning은 Discord, critical은 Discord+email으로 통지한다. 향후 운영 보조 AI는 read-only signal을 분류·runbook 제안만 하며 DB 변경이나 alert close 권한을 갖지 않는다. 상세는 `docs/operations/runtime-and-reliability.md`의 Grafana Cloud 절을 따른다.
- 이번 세션은 구현이 아니라 문서/설계 보강이다. Spring Boot/FastAPI 애플리케이션, JPA Entity, migration은 아직 없다.
- DB/ERD 원본은 `docs/database/schema.dbml`과 `docs/database/modules/*.dbml`이다. 한국관광공사 국문, Odii, 지역 통계, 회원/인증, 탐색, 저장/타임라인, 지도 후기, sync 운영, optional RAG 테이블이 모듈별로 정리돼 있다.
- ERD 시각화는 Azimutt로 확정했다. import 기본 파일은 `docs/database/azimutt/onmaru-schema.azimutt-strict.sql`이고, 재생성 명령은 `node scripts/azimutt-export.mjs`다. Azimutt에서 view를 정리한 뒤 PNG로 export해서 `docs/database/azimutt/exports/`에 저장한다.
- ChartDB, drawDB, ERDCloud, dbdiagram.io, D2, Graphviz 산출물과 관련 스크립트는 혼선을 줄이기 위해 제거했다. `docs/database/README.md`도 Azimutt+PNG 흐름으로 단순화했다.
- 다음 세션에서 할 일: Azimutt에서 권장 view PNG를 export해 `docs/database/azimutt/exports/`에 추가한다. 권장 파일명은 `onmaru-erd-system-overview.png`, `onmaru-erd-identity-auth.png`, `onmaru-erd-catalog-kto.png`, `onmaru-erd-discovery-journey.png`, `onmaru-erd-map-community.png`, `onmaru-erd-operations-sync.png`, `onmaru-erd-ai-rag.png`다.
- 다음 세션에서 할 일: `docs/database/README.md`, `docs/database/azimutt/README.md`, `docs/database/schema.md`를 빠르게 검토해 Azimutt 경로와 DBML source of truth 설명이 일관적인지 확인한다.
- 다음 세션에서 할 일: 아직 정식 ADR로 등록하지 않은 구조 초안은 `docs/decisions/drafts/foundation-architecture.md`에서 확인한다. SSE run lifecycle과 FastAPI RAG ownership을 포함한 최신 설계와 모순 없는지 확인한 뒤, 정식 ADR 생성 및 상태 변경은 구현 착수 전 결정 게이트에서 별도로 한다.
- 다음 세션에서 할 일: 최종 커밋 전 `npx -y -p @dbml/cli dbml2sql docs/database/schema.dbml --postgres`, `node scripts/azimutt-export.mjs`, `git diff --check`를 실행한다.

## Active Restart Session (2026-09-09)

- Request: check PR #48, triage P0, unify W/X candidates, review ADRs, and prepare spec-to-issues publication. Runtime implementation remains gated.
- Actual workspace: `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/prd-planning`; branch `feature/backend-prd-planning`; starting HEAD `2548b42`.
- PR #48 is MERGED into `develop`; head `665a01d5e100b097c9f62c58bb1b368599a866b2`. CI `verify` succeeded; `gemini-review` failed; CodeRabbit succeeded. Review decision field is empty, so human approval is not established by this query.
- This section supersedes the historical branch, PR creation, and restart instructions below. Preserve the merged planning commit; do not rewrite it or reuse its deleted work branch.
- Missing contest inputs and the two references were requested; keep them pending unless supplied.
- Current graph: 21 candidates, waves 0-9, no graph errors; five shared Gradle conflict warnings are intentionally serialized by the integration policy in `docs/planning/implementation-issues.md`.
- ADR-0002 records only the user-selected Spring Boot business API / FastAPI AI roles. D1-D4/E1-E5 remain proposals; review results are in `docs/planning/p0-triage.md`.
- External schema guide has uncommitted changes; metadata alone is not a reproducible snapshot. FE specs are clean at the recorded source commit.
- No GitHub Issues were created, no runtime scaffold started, no push or new PR performed.
- Tracking Issue #49 was created for this planning follow-up PR. No runtime scaffold started.
- Next: resolve scope/reference inputs and W0 security/snapshot/architecture gates; review the concrete `implementation-issues.md` publication draft; publish and verify native Issue relationships before starting implementation.

## Active Revision - 2026-09-11

- 추가 요청: 공급자 독립 회원 ID, 관광공사/오디/지역 통계 수집 스키마와 03:00 KST 누락 복구, 행정구역 집계 지도와 명시적 목록 조회, FE cursor 종료 규칙을 재검토한다. `docs/spring/catalog-ingestion.md`에 후속 설계안을 기록한다. 기존 viewport 자동 조회는 이 제안과 충돌하며 FE 전환 승인 전 구현하지 않는다.
- 추가 요청: 사용자는 장소와 오디 이야기를 담아두기 대상으로 확정하고, 후기 담아두기와 컬렉션은 `project-roadmap.md`의 장기 후보로 남기길 원한다. 여정 저장과 담아두기는 다른 lifecycle로 문서화한다.
- 추가 요청: 기존 FE에 없거나 새로 추가된 기능/API를 FE에게 전달할 별도 md 보고서로 작성한다. `docs/contracts/frontend-handoff.md`에 장소/오디 담아두기, 지도 1.2, VisitReview, run/paging/auth 전환 체크리스트를 기록한다.
- 추가 요청: 내 정보 화면은 단순 컬렉션보다 월간 타임라인을 우선한다. 담아둔 장소, 담아둔 오디 이야기, 저장 여정을 월별 흐름으로 보여주고 FE 전달서에도 상세 flow를 추가한다.
- 추가 요청: DB schema relation이 한눈에 보이지 않아 DBML 기반 ERD를 Level 1 overview와 Level 2 domain detail로 구성한다. 실제 구현/JPA/migration이 없음을 명시하고, 한국관광공사 국문 TourAPI 저장 테이블도 명시적으로 포함한다.

- 사용자 요청: 감사 F01–F21 개선 정책, 헥사고날/DDD·모듈 DAG, DB/인증/저장, 검색 top-k/optional RAG, 지도 전체·지역·인근·viewport 조회, REST/polling/SSE와 실행 자원 예산을 구체화하고 planning 및 FE API 계약에 반영한다.
- 원본 `docs/report/2026-09-10-architecture-review*.md`는 기준선으로 보존한다. 토론 기록과 개선 추적/예상 재평가는 별도 작성한다. 실제 운영 개선 검증이나 감사 finding closure를 의미하지 않는다.
- ADR은 toolkit preflight/related/significance 후 검토 초안을 준비하며, 사용자의 명시적인 초안 승인 전 create/status 변경을 하지 않는다.
- 사용자 확인: 지도 게시글은 기존 온기가 아닌 한옥/한옥 숙박/한옥 카페/전통시장 방문 후 짧은 후기와 좋아요이며 대댓글은 없다. VisitReview를 별도 모델로 설계한다. 기능 설계가 여정 우선 MVP의 출시 필수 범위를 자동 확장하지 않는다.
- 현재 branch는 `feature/onmaru-be-prd-planningv2-2`; 작업 시작 시 `docs/report/`만 untracked. 오래된 아래 snapshot의 branch/plugin/재시작 지시는 현재 작업 지시가 아니다.

- 후속 사용자 요청: 현재 감사·설계 문서를 작업 브랜치에 커밋하고 origin으로 push한다. 이 요청은 ADR 초안 승인이나 PR/merge 승인이 아니다. 커밋 직전 repository hygiene, JSON/문서 링크, 방문 후기 OpenAPI 검증을 다시 통과했다.

### 2026-09-11 산출물과 검증

- 최신 설계는 `docs/architecture/`, `docs/spring/`, `docs/ai/`, `docs/contracts/`, `docs/operations/`으로 책임별 이동했다. `docs/planning/`은 기획/Issue 후보/검토 기록만 유지한다. 전체 권위와 단계 구분은 `docs/README.md`를 따른다.
- 사용자 확인된 지도 글은 VisitReview+ReviewLike이며 기존 Warmth와 별개다. 300자/5줄·댓글 전체 제외는 제안 기본값이다.
- 원본 감사 두 파일 보존, 별도 `docs/report/2026-09-11-design-remediation.md`에 F01–F21 추적과 예상79/100(기준58, +21)을 기록했다. 공식 재감사/운영점수가 아니다.
- 검증: git diff --check 및 CI filesystem baseline 통과. 새 Markdown 상대링크30개/코드fence/JSON 파싱 통과. openapi-spec-validator로 OpenAPI3.1/고유operation6개 확인; 요청 schema 정상·길이·개행·기존mood거절 등7사례 통과. 이는 runtime endpoint 테스트가 아니다.
- ADR toolkit preflight/related(0개)/significance(5개 recommended,13/14/14/14/13)/validate(기존1개,오류0)/check(findings0,warnings0). check는 적용할 구조규칙이 없어 NOT_APPLICABLE이며 전체설계 준수 증명이 아니다.
- 정식 ADR 등록 대기: `docs/decisions/drafts/foundation-architecture.md`의 5개 구조초안. draft는 구현 지시가 아니며, accepted 전환은 별도 결정이다.
- 남은 구현게이트: 전체여정 OpenAPI/FE fixture/DDL/ArchUnit, 실제hosting/부하/보안/kill/restore, F13 workflow 수정, F21 pinned source 전환, Issue graph 발행. 이번 작업은 앱/CI구현이나commit/PR을 수행하지 않았다.


## Session Snapshot

- Date: 2026-09-09 KST.
- Repository: `/Users/yangseunghyeon/orca/workspaces/OnMaruBE/develop`.
- Current branch: `feature/prd-mvp-implements`.
- Git Flow target: create a PR from `feature/prd-mvp-implements` into `develop`.
- Production branch recorded in `AGENTS.md`: `main`.
- Current work type: backend planning, architecture blueprint, operating harness setup, and documentation. No backend application source code has been scaffolded yet.

## Completed Work

- Added local backend reference links under `docs/`:
  - `docs/backend_schema_design_guide.md` points to `/Users/yangseunghyeon/Development/OnMaru/OnMaru-docs/backend_schema_design_guide.md`.
  - `docs/specs` points to `/Users/yangseunghyeon/Development/OnMaru/OnMaruFE/docs/specs`.
- Initialized project operating docs and agent handoff conventions:
  - `AGENTS.md`, `CODEX.md`, `CLAUDE.md`, `GEMINI.md`, `CLINE.md`.
  - `project-roadmap.md`, `improvements.md`, `handoff.md`, `CHANGELOG.md`.
- Added GitHub workflow scaffolding:
  - `.github/workflows/ci.yml`
  - `.github/workflows/release-please.yml`
- Added ADR toolkit bootstrap:
  - `.adr-toolkit.json`
  - `docs/decisions/README.md`
  - `docs/decisions/adr-template.md`
  - `docs/decisions/0001-record-architecture-decisions.md`
- Prepared backend planning artifacts under `docs/planning/`:
  - `README.md`
  - `architecture-blueprint.md`
  - `backend-prd.md`
  - `data-api-design.md`
  - `issue-plan.md`
  - `work-graph.json`
  - `adr-proposals.md`
  - `adr-scores/*.json`
- Added expanded journey exploration planning under `docs/planning/journey-exploration/`:
  - FE source audit.
  - Brainstorming plus Red/Blue review.
  - Story-linked exploration PRD.
  - Recommendation, Hugging Face candidate, RAG, LangGraph, and harness architecture notes.
  - FE API and SSE handoff draft.
  - 2026 contest scoring review.
  - Implementation impact and issue candidates.
- Updated `docs/database/schema.md` with successor planning context.

## Plugin State

- Local Codex plugin is updated and enabled:
  - Plugin: `agent-toolkit-skills@personal`.
  - Version: `0.3.20+codex.20260909022246`.
  - Enabled skills: 29.
  - Installed skill files checked: 231 files matched local source.
  - Cache root: `/Users/yangseunghyeon/.codex/plugins/cache/personal/agent-toolkit-skills/0.3.20+codex.20260909022246`.
- New sessions should discover the updated plugin automatically.
- `adr-toolkit` is a separate local skill, not part of the `agent-toolkit-skills` plugin bundle.

## Architecture Decisions Drafted, Not Yet Final

- Recommended baseline: Modular Monolith + selective Hexagonal Architecture + Lightweight DDD + Gradle Multi-Module.
- Spring Boot owns business APIs and business transactions.
- FastAPI is treated as an external AI/data-processing system behind Spring output ports.
- PostgreSQL/PostGIS is the current relational database recommendation.
- pgvector and AI indexing are proposed for later stages after source qualification.
- Redis, broker, Neo4j, MSA, and heavy graph database adoption are deferred until evidence justifies them.
- ADR proposals exist in planning docs, but only ADR-0001 process initialization has been formally recorded.

## FE And Data Findings To Preserve

- FE docs and source must remain backend input because they include contracts, wireframes, class diagrams, and current data assumptions.
- FE audit found these important backend implications:
  - `/discover` currently uses local fixed mood plans and timers, not real AI.
  - Graph positions are visual percentages, not geographic coordinates.
  - TOP5 and weekly labels are not backed by verified server-side ranking evidence yet.
  - Hanok monthly content can mismatch fallback place data if source IDs are not normalized.
  - Odii ask API currently expects `{ question, filters }`, so new selected-story APIs must preserve compatibility or provide a clear version.
  - Warmth is currently seeded and localStorage-based; server-side warmth needs truthful provenance.
  - Visitor data is regional and delayed; it must not be described as live POI congestion.
  - A hardcoded FE service-key fallback was observed. Do not copy the key into docs or logs; handle removal and rotation as a separate security task.

## Journey Exploration Proposal

- Working product direction: "이야기길" for the `/discover` experience.
- Core user flow: listen to an Odii or editorial story, discover grounded nearby places, pin one place, then replace alternatives without losing the selected place.
- UI contract direction:
  - Horizontal alternatives.
  - Vertical selected detail.
  - Map as geography.
  - Relation graph as explanation and story connection.
  - Typed UI blocks from backend, not arbitrary frontend code generation.
- Backend direction:
  - Spring creates canonical sessions and validates published content.
  - Python handles bounded AI planning, retrieval, embedding, and LangGraph execution.
  - Spring hydrates AI results against current canonical data before returning to FE.
  - SSE streams progress and typed blocks, with snapshot and replay support.

## Issue Planning State

- Existing `docs/planning/work-graph.json` covers W0-W11 only.
- Journey exploration introduced X0-X8 issue candidates, but they are not merged into `work-graph.json` yet.
- No GitHub Issues have been created from the graph yet.
- No PR had been created before this handoff update.
- Before implementation starts, unify W and X issue candidates, remove overlaps, recalculate dependency waves, then create GitHub Issues with `spec-to-issues`.

## Verification Already Done

- Plugin export and validation completed in the earlier session.
- Planning JSON and Markdown sanity checks were run earlier for the expanded journey docs.
- Existing W graph was checked earlier: no graph errors, but five shared build-file conflict warnings were noted.
- Branch protection could not be verified through the GitHub API because this private repository plan blocks that endpoint.
- Current repository still has no backend build manifest, so CI is limited to documentation and repository hygiene checks.

## Known Gaps

- Two user-mentioned external reference materials were not provided in the session and have not been reviewed.
- Contest track is assumed to be the 2026 development track, but the user must confirm the actual submission track.
- Team, budget, auth provider, deployment target, API quota, data license review, and model budget are unresolved.
- No runtime backend, database migration, OpenAPI generation, SSE server, FastAPI service, model inference, or frontend browser integration test exists yet.
- The expanded FE API handoff is a prose draft, not a validated OpenAPI schema.

## Restart Order For Next Session

1. Run `git status --short --branch` and confirm branch `feature/prd-mvp-implements`.
2. Read `AGENTS.md`, `docs/planning/README.md`, and this `handoff.md`.
3. Confirm whether the user wants to keep the current planning commit as-is or split it into smaller commits.
4. Confirm missing contest track and the two missing reference materials.
5. Unify W0-W11 and X0-X8 into one implementation issue graph.
6. Review and approve ADR proposals before locking architecture decisions.
7. Use `spec-to-issues` to create implementation issues.
8. Start backend scaffold only after W0 source/contracts/auth qualification is clear.

## Git Flow PR Preparation

- Commit this documentation/planning state from `feature/prd-mvp-implements`.
- Push the branch to origin.
- Open a PR into `develop`.
- PR body should state that this is a planning/harness PR, not a backend runtime implementation.
- Required checks should pass before merge.
