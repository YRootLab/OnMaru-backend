# handoff.md

## 현재 작업

- **기준일**: 2026-09-26
- **브랜치**: `feature/262-hanok-stamp-book`
- **관련 이슈**: #262
- **상태**: 스키마, 도메인, JDBC/PostGIS, REST API, OpenAPI, FE 인계 및 전체 회귀 검증 완료
- **주요 커밋**: `1a39a4d` 스키마, `dbe6e68` 도메인, `6ea8b90` JDBC, `192121b` REST API, `78bf3a3` OpenAPI, `dac4567` production 경계 보강, `2f6c288` 동시성 검증

## 구현 범위

- V027에 수결 정의·지역 규칙·체크인·지급 테이블과 12개 초기 수결을 추가했다.
- 로그인 회원만 개인 수결첩을 읽고 위치 체크인을 수행한다. 공개 수결 카탈로그에는 개인 상태가 없다.
- active Catalog의 한옥 계열 장소를 PostGIS로 확인하며 `distance - accuracy <= 200m`, accuracy 100m 이하를 적용한다.
- 위도·경도 원문은 저장·응답하지 않는다. 회원당 KST 하루 30회, 같은 장소·15분 구간 중복 방지, UUID 멱등성 키를 적용한다.
- 지역 방문, KST 야간 방문, 서로 다른 5개 권역 방문 수결을 같은 transaction에서 지급한다.
- 공개 랭킹은 개인정보 정책 부재로 제외했다.

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
4. 공개 랭킹 탭은 숨기거나 데모 표시한다.

## 열린 운영 확인 사항

- staging의 실제 Catalog 데이터에 수결 대상 지역 code와 한옥 category가 기대대로 들어오는지 smoke test가 필요하다.
- GPS 오차와 도심 반사 환경에서 200m 정책이 적절한지는 운영 지표 없이 확정할 수 없으므로, 원문 좌표 없이 결과 code·latency만 계측해 조정한다.
- 이 브랜치에서 push, PR 생성, merge는 수행하지 않았다.
