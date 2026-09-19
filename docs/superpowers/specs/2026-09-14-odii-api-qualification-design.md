# Odii API Qualification Design

## 목적

GitHub Issue #61의 A01 범위를 완료하기 위해 한국관광공사 Odii 오디오 가이드 API의 실제 응답, 언어·대본·음원 필드, 오류 envelope, quota·license 근거를 redacted fixture와 manifest로 고정한다. 이 산출물은 후속 A02 수집 adapter와 audio revision 구현이 문서 예시가 아니라 검증된 provider 계약을 기준으로 개발되도록 한다.

## 범위

- 실제 Odii API 호출 결과를 `testing/fixtures/provider/odii`에 저장한다.
- 캡처 provenance, endpoint, request redaction, response hash, field observation, license/attribution 근거를 `docs/reference-snapshots/odii`에 기록한다.
- API key, 원문 민감 query, secret URL query string은 fixture와 manifest에 남기지 않는다.
- 공식 대본과 추정 대본을 구분한다. 이번 캡처의 `script` 필드는 provider가 제공한 official transcript로 기록하고, OnMaru가 생성한 estimated transcript는 포함하지 않는다.

## 아키텍처

캡처 스크립트는 Node.js 표준 라이브러리만 사용한다. `.env.local`에서 `ODII_API_URL`과 `ODII_API_KEY`를 읽어 live API를 호출하고, 저장 전 URL query와 service key를 제거한다. 검증 스크립트는 fixture와 manifest를 다시 읽어 schema shape, hash 일치, secret leakage, 필수 시나리오 존재를 검사한다.

## 산출물

- `scripts/capture-odii-fixtures.mjs`: live Odii 호출과 redacted fixture/manifest 생성.
- `scripts/lib/odii-fixture-validation.mjs`: fixture manifest 검증 로직.
- `scripts/test/odii-fixture-validation.test.mjs`: 검증 로직의 red/green 테스트.
- `testing/fixtures/provider/odii/*.json`: redacted 실제 응답 fixture.
- `docs/reference-snapshots/odii/manifest.json`: fixture hash와 capture metadata.
- `docs/reference-snapshots/odii/README.md`: field mapping, language/script/audio rights qualification 요약.

## 데이터 흐름

1. `.env.local`을 로드한다.
2. 정상·pagination·빈 결과·오류 시나리오를 호출한다.
3. 응답 body를 fixture로 저장하고, request URL은 secret query를 제거한 값만 manifest에 기록한다.
4. fixture 파일의 SHA-256을 manifest에 기록한다.
5. 검증 스크립트가 fixture hash, JSON parse, required scenario, redaction 상태를 검사한다.

## 오류 처리와 제한

- live capture 실패 시 partial fixture를 자동으로 성공 처리하지 않는다.
- HTTP 200 내부 오류 envelope는 성공 fixture와 분리해 provider error scenario로 기록한다.
- 개발계정 호출량은 공공데이터포털 기준 일 1,000건이므로 캡처는 최소 호출 수로 제한한다.
- `audioUrl`은 provider content URL로 유지하되, OnMaru public API에서 그대로 노출할지는 A03에서 별도 결정한다.

## 테스트

- validator unit test는 secret이 들어간 fixture/manifest를 실패시킨다.
- validator CLI는 필수 시나리오 누락과 hash 불일치를 실패시킨다.
- 실제 산출물 생성 후 `node --test scripts/test/odii-fixture-validation.test.mjs`와 `node scripts/validate-odii-fixtures.mjs`를 실행한다.

## PR/Issue 처리

PR 본문에는 `Closes #61`을 사용해 merge 시 이슈를 닫는다. 완료 전에는 Acceptance Criteria의 fixture 확보와 attribution/license 근거 기록이 둘 다 충족되었는지 확인한다.
