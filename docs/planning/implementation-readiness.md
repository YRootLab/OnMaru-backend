# Implementation Readiness

> **목적:** 이 문서는 구현 backlog가 아니다. 설계가 코드로 넘어가기 전에 닫아야 할 결정과 증거를 추적한다. 상태가 `열림`인 항목은 구현 Issue를 만들 때 선행 조건 또는 별도 결정 Issue가 된다.

## 현재 사실

- Spring Boot와 FastAPI 애플리케이션, Gradle/Python build manifest, JPA entity, Alembic/Flyway migration, 배포 환경은 아직 없다.
- DBML은 설계 원본이며 실제 PostgreSQL schema가 아니다.
- 지도/후기 1.2와 여정 SSE의 OpenAPI/JSON Schema/fixture 문서는 존재한다. 아직 FE reducer·Spring serializer에 연결한 실행 contract test는 없다.
- Gemini 무료 tier를 MVP 가정으로 두지만, 실제 이용 한도·정책·비용·지역별 이용 가능 여부는 배포 전에 확인해야 한다.
- 관광 데이터 API는 사용 후보와 저장 모델을 문서화했지만, 실제 호출 결과/페이지네이션/쿼터를 고정한 검증 기록은 없다.

## 레이어별 착수 게이트

| 레이어 | 설계 기준 | 구현 전에 닫을 것 | 완료 증거 |
|---|---|---|---|
| 경계 | [`architecture/module-boundaries.md`](../architecture/module-boundaries.md) | Spring 모듈 package/build 규칙과 FastAPI 내부 API의 버전 규칙 | ArchUnit 또는 Modulith test 초안, Python import boundary test |
| Spring API | [`contracts/rest-api.md`](../contracts/rest-api.md), [`journey.openapi.yaml`](../contracts/openapi/journey.openapi.yaml) | 지도 1.2 단일 계약, AI 여정 REST+SSE event/reconnect, auth/error/idempotency | FE/BE serializer와 reducer에 fixture 연결, SSE replay/reset contract test |
| Spring identity | [`spring/identity-and-journey.md`](../spring/identity-and-journey.md) | OAuth provider, guest-to-member 승격, CSRF/state, 탈퇴·보존 | callback state test case, ownership matrix, retention decision |
| Spring catalog | [`spring/catalog-ingestion.md`](../spring/catalog-ingestion.md) | 수집 adapter의 provider별 pagination, 오류 envelope, quota, licence, schedule | 첫 adapter integration test의 redacted live capture와 response fixture; 통과 전 LIVE_CANONICAL 비활성 |
| Database | [`database/migration-and-concurrency.md`](../database/migration-and-concurrency.md) | owner XOR, active-run partial unique, admission lock, report/audit 제약, migration order | reviewed Flyway DDL, Testcontainers concurrency integration test, restore test plan |
| FastAPI policy | [`ai/journey-guardrails.md`](../ai/journey-guardrails.md) | scope/금칙/privacy taxonomy, guest 2/member 5 quota storage와 reset | Korean adversarial fixture, quota concurrency cases, false-positive review |
| FastAPI provider | [`ai/internal-service-authentication.md`](../ai/internal-service-authentication.md) | Gemini adapter SDK/version, service token, timeout, output schema, model failure mapping | fake adapter test, real sandbox response, token audience/replay/rotation test, JSON schema validation |
| FastAPI RAG | [`ai/corpus-sync-contract.md`](../ai/corpus-sync-contract.md) | Spring manifest/tombstone/ACK와 FastAPI-only `ai` schema ingest, 활성화 기준 | pinned corpus sync eval, allowlist violation=0, cost/quality comparison |
| UGC moderation | [`operations/moderation.md`](../operations/moderation.md) | 신고 reason, 운영 판정 채널, audit 보존·알림 책임 | report/moderation integration test, operator drill, no public DB write |
| Runtime | [`operations/runtime-and-reliability.md`](../operations/runtime-and-reliability.md) | scheduler, secret store, run timeout/cancel, LKG/lease/fence, Actuator/Micrometer/OTel→Grafana Cloud signal과 severity별 Discord/email alert | kill/restart scenario, stale worker scenario, trace/log correlation, Grafana dashboard와 warning/critical test notification |
| Delivery | [`decisions/README.md`](../decisions/README.md) | ADR draft 5개의 정식 등록 여부와 GitHub Issue graph | accepted/proposed ADR 상태, independent Issue acceptance criteria |

## 구현 시 생성될 산출물의 자리

아래는 지금 만들지 않는다. Issue가 발행된 뒤 구현과 함께 추가될 위치다.

```text
spring/                    Spring Boot source and tests (future application root)
ai/                        FastAPI source and tests (future application root)
db/migration/              reviewed migrations (future)
docs/contracts/openapi/    generated or hand-authored public API specifications
docs/contracts/fixtures/   FE/BE contract fixtures (future)
docs/ai/evals/             prompt and safety evaluation fixtures (future)
docs/operations/runbooks/  tested operational runbooks (future)
```

문서 폴더인 `docs/spring/`, `docs/ai/`는 구현 source 디렉터리의 대체물이 아니다. 지금은 설계의 책임 소유자를 보여 주며, source layout은 scaffold Issue에서 ADR와 이 문서의 경계를 검증한 뒤 정한다.

## Issue 발행 기준

각 GitHub Issue는 다음 다섯 가지를 가져야 한다.

1. 하나의 소유 레이어와 관련 설계 문서 링크
2. 입력/출력 또는 DB 변화의 명시적 계약
3. 실패·권한·동시성 또는 외부 API 오류의 처리 방식
4. 독립 실행 가능한 acceptance criteria와 검증 명령
5. 선행 결정 또는 다른 Issue의 명확한 dependency

이 기준을 만족하지 않는 항목은 아직 Issue가 아니라 이 문서 또는 기획 문서의 열린 질문으로 둔다.
