# handoff.md

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
