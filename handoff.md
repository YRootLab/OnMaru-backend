# handoff.md

## 현재 세션: #368 직렬 기준선 및 #364 required verify 전환

- **기준일**: 2026-09-26
- **브랜치**: `feature/364-verify-fan-in`
- **관련 이슈**: #368, #364, 상위 #255
- **사용자 요청**: #368 immutable baseline artifact를 완성하고, #364의 `CI / verify`를 Toolkit 기반 fan-out/fan-in으로 전환한다.
- **설계**: `docs/superpowers/specs/2026-09-26-required-verify-fan-in-design.md`
- **선행 증적**: develop SHA `34276f201ce6f7b5ffb6e7ab2784eb584a91a699`, serial run `36159816646`, `36160594916`, `36161322635`, 중앙값 400초
- **다음 단계**: baseline collector dispatch 및 artifact 검증 후, `ci-fan-in` fixture를 RED로 만들고 `ci.yml`을 구현한다.

---

## 현재 작업

- **기준일**: 2026-09-26
- **브랜치**: `feature/342-fe-api-contract-fixes`
- **PR**: #395 `feat: FE 요청 API 계약과 JDBC 파이프라인 완성` → `develop`
- **커밋**: 기능 구현 `fcf53ca`; 최신 `develop` 병합 완료
- **관련 이슈**: #342, #343, #344, #346, #393
- **후속 이슈**: #392(DataLab 운영 smoke), #360(Redis cache)

## 이 브랜치에서 완료한 작업

### FE API 계약

- 기존 versioned API를 FE 호환 경로 `/api/map/places`, `/api/map/heat`, `/api/place/{placeId}`에서도 제공한다.
- 지도 `visited`는 장소 자체 속성이 아니라 사용자의 방문/온기 후기 존재 의미로 정리했다.
- `/api/map/warmth` 별도 모델을 만들지 않고 VisitReview를 온기 후기의 단일 API로 사용하도록 FE 문서에 명시했다.
- 한옥 도감 목록에서는 `HANOK_CAFE`를 제외하되 홈 큐레이션 정책은 변경하지 않았다.

### VisitReview와 JDBC 영속화

- VisitReview에 선택 필드 `mood`, `score`, `tags`를 추가하고 validation·OpenAPI·fixture를 갱신했다.
- 공개 `placeId`와 Catalog UUID의 안정적인 일대일 mapping, 작성 시점 장소명·지역·좌표 snapshot을 PostgreSQL에 저장한다.
- 후기·좋아요·신고·moderation audit·멱등성 receipt를 JDBC로 전환했으며 production에서 in-memory fallback을 사용하지 않는다.
- 후기 생성과 idempotency receipt는 같은 `JdbcTransactionRunner`를 공유한다. 실패 시 둘 다 rollback되고 동일 키 재시도가 가능함을 PostgreSQL 통합 테스트로 검증했다.
- 좋아요 갱신은 `SELECT ... FOR UPDATE`로 직렬화해 lost update를 방지한다.
- legacy 후기는 V026에서 공개 ID와 장소 snapshot을 backfill한다. 좌표를 복구할 수 없는 행은 `(0, 0)`으로 노출하지 않고 공개 조회에서 제외한다.

### 스크린 한옥

- 스크린 한옥 placement snapshot을 `catalog_screen_hanok_placements`에 저장하는 JDBC store를 추가했다.
- production profile은 JDBC store를 사용하며 `mediaType`·`region` 필터 계약과 재생성 store 조회를 검증했다.

### DataLab 방문자 수

- DataLab 광역·기초 방문자 API를 전국 단위로 수집하고 외지인(`touDivCd=2`) 관측만 `visitorCount`로 제공한다.
- 공식 v4.1 매뉴얼과 실제 응답을 근거로 다음 mapping을 V025에 등록했다: `kr-11→SIDO:11`, `kr-11-jongno→SIGUNGU:11110`, `kr-45→SIDO:52`, `kr-45-jeonju→SIGUNGU:52110`.
- 관측값은 전용 dataset revision에 JDBC로 저장하고, 모든 활성 검증 지역의 관측이 완전할 때만 원자적으로 게시한다. 실패하면 이전 정상 revision을 유지한다.
- production Insights 조회와 VisitReview 지역 응답은 활성 revision만 읽는다. 매일 03:30 KST 수집 scheduler를 연결했다.
- DataLab 소수 방문자 수는 공개 `int64` 계약에 맞춰 가장 가까운 1명으로 반올림한다.

### 구조·문서·마이그레이션

- V018~V026 Flyway migration과 checksum registry를 추가했다.
- ADR-0014에서 VisitReview의 Catalog UUID + immutable snapshot 결정을, ADR-0015에서 DataLab 전용 revision 게시 결정을 기록했다.
- DB schema, 운영 runbook, FE handoff, API delta 문서를 구현 상태에 맞게 갱신했다.

## 검증 기록

- `./gradlew test --no-daemon --max-workers=1` — 성공, 53 tasks
- `JdbcIdempotencyStoreTests` — 후기/receipt 동시 rollback 및 재시도 성공
- `node --test scripts/test/*.test.mjs` — 106 passed(최신 `develop` 병합 후 재검증)
- `python3 -m pytest scripts/test/test_contract_validation.py` — 9 passed
- `bash scripts/verify-contracts` — 성공
- `uv run pytest` (`ai/`) — 212 passed, 1 skipped
- `uv run ruff check && uv run mypy` (`ai/`) — 성공
- offline AI evaluation gate — 성공
- ADR toolkit validate — 15 ADR, 오류 없음
- PR #395의 `verify`, PostgreSQL Restore Drill, CodeRabbit — 성공(이전 head 기준)

## 다음 단계와 열린 위험

1. 이 handoff 정리와 최신 `develop` 병합 커밋을 push한 뒤 PR #395의 새 `verify`가 통과하는지 확인한다.
2. PR #395 병합 후 #393 acceptance criteria와 이슈 상태를 재확인하고, `develop` 대상 PR의 자동 종료 제한에 따라 필요하면 검증 명령과 PR을 적은 코멘트로 수동 종료한다.
3. 배포 후 #392에서 실제 운영 키와 Catalog 데이터로 DataLab 수집·활성 revision·`visitorCount`를 smoke 검증한다.
4. #343은 운영 screen-hanok 게시 데이터가 1건 이상인지 확인한 뒤 종료 여부를 판단한다.
5. Redis cache는 사용자 요청대로 이번 PR에서 제외했다. 필요 시 #360에서 별도로 구현한다.
6. #342, #344, #346은 Redis 및 별도 계약이 필요한 umbrella 항목이 남아 있어 현재 열린 상태를 유지한다.

저장소에는 공공데이터·Gemini 비밀 키를 커밋하지 않는다. 로컬 또는 배포 환경변수로만 주입한다.
