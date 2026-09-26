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

## 병합된 후속 작업: #307 / #382

- **브랜치**: `fix/307-odii-production-jdbc-store`
- **관련 이슈**: #307, #382
- **상태**: production의 in-memory `AudioRevisionStore` 선택 경쟁 재현 및 수정, 운영 재배포 전

## 확인한 운영 증거

- Render health는 `UP`이지만 Odii 목록·홈·검색·근처·추천 API는 모두 `503 SERVICE_UNAVAILABLE`이다.
- Neon에서 `odii-audio` lease generation은 증가하지만 dataset revision, active pointer, stage, story, sync run은 모두 0행이었다.
- 배포 API에 초기화 패치 이후 추가된 `/api/stories`가 존재하므로 단순 구버전 배포만으로는 설명되지 않는다.
- `JdbcAudioRevisionStore`가 선택되면 bean 생성 시 bootstrap revision이 반드시 생성된다. 운영 DB 0행은 JDBC store가 선택되지 않았다는 증거다.

## 구현 내용

- production에서는 in-memory `AudioRevisionStore` fallback을 비활성화하고 JDBC store를 조건 없이 등록하도록 변경했다.
- `OdiiStoryQueryStore`가 명시적인 `AudioRevisionStore`를 요구하도록 변경해 production DB 설정 오류를 fail-fast 처리한다.
- 설정 등록 순서를 반대로 하거나 DataSource 설정을 나중에 처리하는 통합 테스트를 추가했고, 수정 전 실패·수정 후 성공을 확인했다.
- `troubleshooting-worklog/26.09.19 production-audio-revision-store-and-neon-persistence.md`의 평문 DB credential을 제거했다.
- 현재 working tree에는 동일 credential 패턴이 없지만 기존 Git 이력에는 남아 있으므로 폐기·교체와 이력 정리가 필요하다.

## 검증

- `JdbcAudioRevisionStoreIntegrationTests.productionProfileSelectsJdbcRevisionStoreRegardlessOfConfigurationRegistrationOrder` — 성공
- `JdbcAudioRevisionStoreIntegrationTests.productionProfileSelectsJdbcRevisionStoreWhenDataSourceConfigurationIsProcessedLater` — 성공
- `./gradlew :modules:audio:test :adapters:tourism-api:test :apps:spring-api:test --tests '*Odii*' --tests '*JdbcAudioRevisionStoreIntegrationTests'` — 성공
- `./gradlew test --no-daemon --max-workers=1` — 성공, 53 tasks
- `bash scripts/verify-contracts` — 성공
- `git diff --check` — 성공

## 다음 단계

1. PR을 `develop` 대상으로 생성하고 `verify` 통과를 확인한다.
2. `master` 릴리스 및 Render 재배포 후 bootstrap revision → stage → story → active pointer 순서로 Neon을 확인한다.
3. stage가 `SOURCE_FAILED`이면 Render provider 오류와 `storyBasedSyncList` 실제 응답을 추가 진단한다.
4. 모든 Odii API 200 확인 후 #307을 종료한다. #382의 run 이력·phase 관측성은 별도 구현을 계속한다.
