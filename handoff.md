# handoff.md

## 현재 작업: #403

- **기준일**: 2026-09-26
- **브랜치**: `feature/403-ci-hybrid-lanes`
- **관련 이슈**: #403(PR CI 대기 시간 단축)
- **목표**: 모듈당 독립 runner shadow benchmark를 필수 CI로 전환하지 않고, Java 공유 workspace lane과 독립 hygiene/contract/AI lane으로 기존 직렬 `verify`보다 빠른 PR 검증을 만든다.

### 설계와 검증 기준

- Java는 단일 Gradle invocation으로 묶어 Spring API가 의존 모듈의 compile/test 산출물을 재사용한다.
- hygiene와 contract는 항상 실행하고, Java/AI는 PR 변경 경로에 따라 선택한다. workflow·Gradle·CI test 변경은 fail-safe full-suite로 승격한다.
- 최종 `verify`는 선택된 lane이 `success`, 선택되지 않은 lane이 `skipped`일 때만 성공한다.
- 전환 성과는 동일 SHA의 GitHub Actions 3회 측정 중앙값이 기존 직렬 CI 중앙값 5분 04초보다 작은 경우에만 인정한다.

### 현재 검증

- `node --test scripts/test/*.test.mjs` — 117 passed
- Java shared lane command — 성공, 로컬 cold build 6분 56초(로컬 수치는 GitHub runner 성능 비교에서 제외)

### 다음 단계

1. PR을 열어 GitHub Actions의 full-suite 후보 CI를 확인한다.
2. 통과한 동일 SHA를 수동으로 3회 실행해 기존 직렬 baseline과 비교한다.
3. 중앙값이 개선되지 않으면 merge하지 않고 #403에 증적과 다음 병목을 남긴다.

## 현재 작업: #392 / #399

- **기준일**: 2026-09-26
- **브랜치**: `feature/399-datalab-registry-smoke`
- **관련 이슈**: #399(DataLab mapping registry), #392(staging 운영 smoke)
- **PR**: #401 `feat(insights): DataLab registry와 staging smoke 구축` → `develop`
- **설계/계획**: `docs/superpowers/specs/2026-09-26-datalab-registry-smoke-design.md`, `docs/superpowers/plans/2026-09-26-datalab-registry-smoke.md`

### 완료한 작업

- V027에 provenance와 `PENDING`/`ACTIVE`/`REJECTED` 상태를 가진 DataLab 전용 region mapping registry를 추가했다.
- 수집 adapter를 registry 기반 fail-closed 처리로 전환하고 provider 누락 값은 `null`/`NOT_AVAILABLE`로 보존한다.
- skip/quarantine 결과 metric, production 전용 보호 operations endpoint, token rotation을 추가했다.
- 실제 수집·DB active revision·공개 API 계약을 확인하고 sanitized artifact를 남기는 staging smoke workflow를 추가했다.
- smoke 성공 시에만 #392를 닫고 실패 시에는 실행 링크를 남긴 채 열린 상태를 유지한다.

### 검증 기록

- `./gradlew test --no-daemon --max-workers=1` — 성공, 54 tasks
- `node --test scripts/test/*.test.mjs` — 112 passed
- `python3 -m pytest scripts/test/test_contract_validation.py` — 9 passed
- `bash scripts/verify-contracts` — 성공
- `uv run pytest` (`ai/`) — 212 passed, 1 skipped
- `uv run ruff check && uv run mypy` (`ai/`) — 성공
- 독립 code review — Critical/Important 미해결 항목 없음, Ready to merge

### 다음 단계와 열린 위험

1. PR #401의 CI `verify`와 review 결과를 확인한다.
2. GitHub `staging` environment에 URL, operations token, DataLab service key, read-only DB URL을 설정한다.
3. V027과 runtime을 staging에 배포한 뒤 `DataLab Staging Smoke`를 실행한다.
4. #399는 PR 병합 후 reconcile한다. #392는 실제 staging smoke 성공 전까지 닫지 않는다.

저장소와 artifact에는 공공데이터 key, operations token, DB URL, provider payload를 남기지 않는다.
