# handoff.md

- **Date**: 2026-09-24 CI baseline and fan-out evidence report
- **Branch**: `docs/376-ci-performance-evidence-report`
- **Related Issue**: #376 (depends on #368 serial baseline and #365/#377 fan-out evidence)
- **Scope**: sanitized aggregate report and measurement/decision contract only; no CI topology, runtime command, or release-gate changes
- **Evidence**: serial CI run 35940692137; fan-out run 35941255870 (failed/incomparable because AI `uv` runtime is absent)
- **Date**: 2026-09-24 AI module benchmark runtime preparation
- **Branch**: `fix/377-ai-module-benchmark-runtime`
- **Related Issue**: #377 (blocks #365)
- **Scope**: consumer-owned AI catalog command and catalog contract test only; no reusable workflow permission/topology change
- **Verification**: contract expectation first failed against bare `uv run pytest`; fresh runner now bootstraps `uv` and runs pytest from `ai/`

- **Date**: 2026-09-23 serial CI baseline manifest 구현 시작
- **Branch**: `feature/368-serial-baseline-manifest`
- **Related Issue**: #255 root, #368 serial baseline manifest
- **Planned**: serial run 입력 검증·comparable baseline manifest·운영 수집 절차
- **Verification**: TDD로 `scripts/test/serial-baseline.test.mjs`부터 추가
- **Open Risk**: 현재 CI에는 `workflow_dispatch`가 없으므로 실제 develop serial run 3회의 artifact URL은 자연 발생 develop run 또는 별도 dispatch 지원 후 수집해야 한다.
- **Date**: 2026-09-23 Gradle CI 성능 profile 구현 및 PR 준비
- **Branch**: `feature/369-gradle-ci-performance-profile`
- **Related Issue**: #369
- **Changed**: opt-in Gradle worker/cache profile, 실효 설정 JSON report task, 계약 테스트
- **Verified**: `node --test scripts/test/gradle-ci-performance.test.mjs`, `./gradlew --init-script build-logic/ci-performance.gradle.kts ciPerformanceProfile -Ponmaru.ci.performance.enabled=true --no-daemon`
- **Open Risk**: #364가 CI workflow에서 init script와 profile flag를 호출해야 실제 CI fan-out 실행에 적용된다.
- **Date**: 2026-09-23 AI pytest worker profile 및 duration evidence 구현
- **Branch**: `feature/370-pytest-ci-profile`
- **Related Issue**: #370
- **Changed**: `ai/pyproject.toml`, `ai/uv.lock`, `ai/scripts/pytest-ci-profile.py`, `scripts/test/pytest-ci-profile.test.mjs`
- **Verified**: `uv run --project ai ruff check ai/scripts/pytest-ci-profile.py`, `uv run --project ai pytest ai/tests` (212 passed, 1 skipped), `node --test scripts/test/pytest-ci-profile.test.mjs` (3 passed), `ONMARU_PYTEST_WORKERS=2 uv run --project ai python ai/scripts/pytest-ci-profile.py --evidence-dir <tmp> --` (worker profile, JUnit/duration evidence 확인)
- **CI Regression Fix**: setup-uv 이전 Node phase는 시스템 `python3`와 dry-run forced fallback만 사용하도록 fixture를 변경했다. `PATH=/usr/bin:/bin node --test scripts/test/pytest-ci-profile.test.mjs`에서 3건 통과했고, dry-run은 JUnit XML을 생성하지 않는다는 계약을 명시적으로 검증한다.
- **Open Risk**: 저장소 루트 `uv run --project ai pytest`는 기존 `scripts/test/test_contract_validation.py`가 `openapi_spec_validator`를 요구하지만 AI dev dependency에 없어 7건 실패한다. #370 독점 범위 밖이며 AI 테스트 경로는 통과했다.

- **Date**: 2026-09-23 모듈별 병렬 CI·benchmark control-plane Wave 0
- **Branch**: `feature/363-ci-baseline-catalog`
- **Related Issue**: #255 root, #363 catalog, toolkit #31/#33
- **Changed**: `.github/benchmark-modules.yml`, catalog coverage test
- **Verified**: `node --test scripts/test/module-catalog.test.mjs`, `node --test scripts/test/*.test.mjs` (88 passed)
- **Verified CI**: PR #367 `verify` passed (2026-09-23).
- **Open Risk**: serial baseline 3회 evidence 수집은 #368, CI fan-out은 #364에서 수행한다.

- **Date**: 2026-09-23 소리마루 Single Source of Truth API 구현 및 PR 준비
- **Branch**: `feature/345-sorimaru-single-source-of-truth`로 rename 예정
- **Related Issue**: #345
- **Changed**: `/api/stories`, `/api/stories/nearby`, `/api/recommendation`, FE 전달 보고서
- **Verified**: `./gradlew test --no-daemon --max-workers=1`, `bash scripts/verify-contracts`
- **Open Risk**: Redis 캐시는 의도적으로 제외. 운영 active Odii dataset 배포 확인 필요

- **Date**: 2026-09-21 홈 API 운영 검증 및 후속 이슈 정리
- **Branch**: `feature/hanok-api-related-improvements` (master 576abfa 병합 완료, 병합 커밋 33ccf94)
- **Related Issues**: #264 (코멘트 남김), 신규 #306 (지역별 오디오 그룹 API), 신규 #307 (trending-sounds 503 원인 분석)
- **Verified**:
  - 운영 v0.3.12 배포 후 `GET /api/v1/home/curated-courses?limit=25` → 25개 중 24개 실제 TourAPI 썸네일 URL, URL 다운로드 테스트 200 OK (이미지 문제 해결)
  - cursor 페이징 정상 동작 (items/hasMore/nextCursor 계약)
  - 운영 Neon DB 조사: `audio_revision_stages`/`audio_story_versions`/`catalog_active_datasets` 0행 → trending-sounds 503 원인은 Odii 동기화 staging 실패. `operations_sync_leases` odii-audio generation 15로 스케줄러는 실행 중
- **Next Steps**:
  - Render 로그에서 `odii sync failed` 확인, `ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT` 유효성 점검 (#307)
  - `GET /api/v1/audio/regions` 지역 그룹 카운트 API 구현 (#306)
# Issue #380 — CI 성과 보고 프롬프트와 비개발자용 기준선 보고서

- Branch: `docs/380-accessible-ci-report-prompt`
- Scope: 재사용 가능한 보고서 프롬프트를 추가하고, #376의 CI 기준선 보고서를 비개발자도 읽을 수 있는 문단형 보고서와 짧은 Mermaid로 재구성한다.
- Changed: `docs/prompts/ci-performance-report.md`, `docs/reports/2026-09-24-ci-parallelization-baseline.md`
- Verification: `node --test scripts/test/*.test.mjs` (94 passed), 보고서 표현 계약 검사(1 Mermaid, 6 nodes), `git diff --check`.
- Next: PR을 열고 `verify`가 통과하면 develop으로 병합한 뒤 #380을 정리한다.
