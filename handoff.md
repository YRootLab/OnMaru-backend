# handoff.md

## 현재 작업: Issue #262 익명 수결 랭킹 후속

- **기준일/브랜치**: 2026-09-27 / `feature/262-hanok-stamp-book`
- **상태**: V029 `stamp_ranking_profiles`, 익명 프로필 생성·철회, JDBC 실시간 집계, 공개·개인 REST API와 OpenAPI 1.3 구현. V030과 production `JdbcIdentityStore`로 OAuth 회원 원장을 수결 FK와 연결하고, 탈퇴 cleanup에 체크인·수결·랭킹 profile 및 체크인 idempotency receipt 삭제와 DELETING 회원 재생성 차단을 포함했다. FE 인계 및 운영 계약 문서를 현재 구현에 맞춰 갱신했다. PR #402의 충돌 해결·갱신·merge를 진행 중이다.
- **API**: 공개 `GET /api/v1/stamps/leaderboard`, 회원 `GET/PUT /api/v1/me/stamp-ranking`. 개인 PUT은 session cookie와 CSRF가 필요하고 철회는 재참여 5초 제한과 무관하다.
- **변경 파일**: `docs/contracts/rest-api.md`, `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`, `docs/superpowers/specs/2026-09-26-hanok-stamp-book-design.md`, `handoff.md`, `CHANGELOG.md`.
- **구현 커밋**: `a79e7c3` V029, `afd0a22` domain, `041392a` JDBC, `af7b59e` REST, `860d699` OpenAPI. V030 identity 연결과 탈퇴 cleanup 보강 커밋은 최종 리뷰 수정 보고서를 확인한다.
- **문서 검증**: `bash scripts/verify-contracts --contracts-only`, 변경 문서의 로컬 Markdown 링크 경로 확인, `git diff --check` 모두 통과.
- **다음 단계/열린 위험**: FE가 실제 세션·CSRF·429·철회 후 재조회 흐름을 연결하고 staging에서 동의/철회 화면과 운영 데이터 성능을 확인한다. PR #402 merge 뒤 Issue #262 상태를 reconcile한다.

## 이전 작업 기록: #392 / #399

- **기준일**: 2026-09-26
- **브랜치**: `feature/262-hanok-stamp-book`
- **관련 이슈**: #262
- **상태**: 스키마, 도메인, JDBC/PostGIS, REST API, OpenAPI, FE 인계 및 전체 회귀 검증 완료. `develop` 대상 PR 생성 준비 완료
- **주요 커밋**: `1a39a4d` 스키마, `dbe6e68` 도메인, `6ea8b90` JDBC, `192121b` REST API, `78bf3a3` OpenAPI, `dac4567` production 경계 보강, `2f6c288` 동시성 검증

## 구현 범위

- V028에 수결 정의·지역 규칙·체크인·지급 테이블과 12개 초기 수결을 추가했다.
- 로그인 회원만 개인 수결첩을 읽고 위치 체크인을 수행한다. 공개 수결 카탈로그에는 개인 상태가 없다.
- active Catalog의 한옥 계열 장소를 PostGIS로 확인하며 `distance - accuracy <= 200m`, accuracy 100m 이하를 적용한다.
- 위도·경도 원문은 저장·응답하지 않는다. 회원당 KST 하루 30회, 같은 장소·15분 구간 중복 방지, UUID 멱등성 키를 적용한다.
- 지역 방문, KST 야간 방문, 서로 다른 5개 권역 방문 수결을 같은 transaction에서 지급한다.
- 당시 체크인 범위에서는 공개 랭킹을 제외했다. 2026-09-27 후속 설계와 구현에서 명시적 참여형 익명 랭킹을 추가했다.

## API와 문서

- `GET /api/v1/stamps`: 공개 수결 정의
- `GET /api/v1/me/stamp-book`: 로그인 회원 개인 수결첩, `no-store`
- `POST /api/v1/places/{placeId}/check-ins`: 세션·CSRF·`Idempotency-Key` 필수 위치 체크인
- 기계 판독 계약: `docs/contracts/openapi/hanok-stamps.openapi.yaml`
- FE 인계: `docs/toFE/hanok-stamp-book-api-handoff-2026-09-26.md`
- 설계: `docs/superpowers/specs/2026-09-26-hanok-stamp-book-design.md`
- 구현 계획: `docs/superpowers/plans/2026-09-26-hanok-stamp-book.md`

## 완료된 집중 검증

- `./gradlew :modules:stamp:test --no-daemon` — 성공
- `./gradlew :apps:spring-api:test --tests '*JdbcStampMigrationTests' --no-daemon` — 성공
- `./gradlew :apps:spring-api:test --tests '*JdbcStampStoreTests' --no-daemon` — 성공
- `./gradlew :apps:spring-api:test --tests '*StampWebBoundaryTests' --no-daemon` — 성공
- `node --test scripts/test/migration-policy.test.mjs` — 성공
- `python3 -m pytest scripts/test/test_contract_validation.py -q` — 10 passed
- `bash scripts/verify-contracts --contracts-only` — 성공

## 최종 회귀 검증

- `./gradlew test --no-daemon --max-workers=1` — 성공, 57 tasks
- `node --test scripts/test/*.test.mjs` — 106 passed
- `python3 -m pytest scripts/test/test_contract_validation.py -q` — 10 passed
- `bash scripts/verify-contracts` — 성공, R1·identity/saved·Journey·R2·생성 DB artifact 포함
- `uv run pytest` (`ai/`) — 212 passed, 1 skipped
- `uv run ruff check && uv run mypy` (`ai/`) — 성공
- offline AI evaluation gate — quality·safety·latency·cost·determinism 모두 PASS
- `node scripts/verify-planning-inputs.mjs`와 `node scripts/validate-odii-fixtures.mjs` — 성공
- 독립 코드 리뷰에서 발견한 Java time receipt 직렬화, domain exception 503 변환, 누락 좌표 필드, DB 반경 제약, 멱등성 오류 코드 불일치를 수정하고 production JDBC 집중 테스트를 재통과했다.

## FE 다음 단계

1. `/stamps` 화면의 `onmaru_hanok_stamps_v1` 기반 획득 판정을 서버 API로 교체한다.
2. 브라우저 위치 권한 → CSRF 발급 → 같은 UUID key를 재사용하는 체크인 흐름을 연결한다.
3. 개인 수결첩 조회가 성공한 뒤 legacy localStorage 키를 제거한다. 데모 도장을 서버로 이전하지 않는다.
4. 공개 랭킹 탭은 후속 `GET /api/v1/stamps/leaderboard`와 개인 참여 API에 연결한다.

## 열린 운영 확인 사항

- staging의 실제 Catalog 데이터에 수결 대상 지역 code와 한옥 category가 기대대로 들어오는지 smoke test가 필요하다.
- GPS 오차와 도심 반사 환경에서 200m 정책이 적절한지는 운영 지표 없이 확정할 수 없으므로, 원문 좌표 없이 결과 code·latency만 계측해 조정한다.
- PR merge 뒤 #262 상태를 조회하고, `develop`과 기본 브랜치 `master` 차이로 자동 종료되지 않으면 PR·검증 명령을 기록한 코멘트와 함께 수동 종료한다.
- **브랜치**: `feature/399-datalab-registry-smoke`
- **관련 이슈**: #399(DataLab mapping registry), #392(staging 운영 smoke)
- **PR**: #401 `feat(insights): DataLab registry와 staging smoke 구축` → `develop`
- **설계/계획**: `docs/superpowers/specs/2026-09-26-datalab-registry-smoke-design.md`, `docs/superpowers/plans/2026-09-26-datalab-registry-smoke.md`
## 현재 작업: #307 / #382

- **기준일**: 2026-09-27
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

1. `develop` 대상 PR의 `verify` 통과와 병합 가능 상태를 확인한다.
2. `master` 릴리스 및 Render 재배포 후 bootstrap revision → stage → story → active pointer 순서로 Neon을 확인한다.
3. stage가 `SOURCE_FAILED`이면 Render provider 오류와 `storyBasedSyncList` 실제 응답을 추가 진단한다.
4. 모든 Odii API 200 확인 후 #307을 종료한다. #382의 run 이력·phase 관측성은 별도 구현을 계속한다.
