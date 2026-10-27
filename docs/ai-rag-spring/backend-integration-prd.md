# OnMaru AI RAG 백엔드 통합 PRD

> 상태: 구현 전 PRD. 제품의 RAG 동작과 AI Engine 내부 모듈은 [onmaru-ai-engine의 AI-RAG 정본](https://github.com/YRootLab/onmaru-ai-engine/tree/main/docs/ai-rag)을 따른다. 이 문서는 OnMaru-backend 저장소에서 구현·운영해야 할 Spring, DB, 보안, 관측, 배포 작업을 정한다. AI 기능이 현재 운영 중이라는 뜻은 아니다.

## 1. 목적

별도 저장소인 `onmaru-ai-engine`의 FastAPI 서비스를 기존 OnMaru Spring Boot·PostgreSQL·Nginx와 통합한다. 첫 제품 경로는 홈 여정의 대화형 AI 검색이다. Spring은 사용자/API 경계와 업무 데이터의 정본을 유지하고, AI Engine은 질문 처리·검색·근거 선택·생성·평가를 수행한다. 저장소 분리는 서버 분리를 뜻하지 않는다. 초기 운영 목표는 기존 Lightsail 한 대에서 공존시키는 것이다.

## 2. 범위와 소유권

| 책임 | OnMaru-backend | onmaru-ai-engine |
|---|---|---|
| 사용자 인증, 사용량·남용 제한, conversation/thread 소유권 | 정본·구현 | 인증 주체가 전달한 제한된 context만 사용 |
| 공개 API, SSE 연결·재접속·이벤트 영속화, 최종 응답 검증 | 정본·구현 | 내부 요청/응답 계약 준수 |
| 회원·대화·장소 업무 데이터와 정형 SQL 권한 | 정본·구현 | SQL 문자열/업무 DB 임의 접근 금지 |
| `PlaceQueryPort`와 승인된 장소 조회 | application service/repository 구현 | 계획을 제출하고 제한된 결과만 소비 |
| RAG corpus, chunk/vector/BM25, query processing, retrieval, ranking, generation | DB extension·접근 경계·migration 운영 지원 | 내부 구현·평가 |
| Spring 관측 projection과 공개 원천 상태 조회 | 읽기 전용 내부 tool/adapter 제공 | quality harness가 승인된 scope로 조회 |
| 품질 큐·agent workflow·격리 실험 | Grafana/운영 조회 연계에 필요한 계약 제공 | AI artifact, 분석·실험·승인 workflow |
| 운영 배포, Nginx·Compose·secret·backup·rollback | 정본·구현 | 테스트된 immutable image와 versioned contract 발행 |

## 3. 사용자 및 시스템 흐름

1. 사용자는 기존 Spring API로 여정 대화를 생성하거나 이어간다. Spring은 인증, 회원별 quota/rate limit, 입력 검증, thread 접근권한, 대화 이력 정책을 적용한다.
2. Spring은 내부 REST로 FastAPI plan/execute를 호출하며 W3C Trace Context와 versioned contract를 전달한다. 브라우저는 FastAPI를 직접 호출하지 않는다.
3. AI Engine이 정형 장소 조회를 필요로 하면 결정적 Search Router가 typed plan을 만든다. Spring `PlanAuthorityGate`가 필드·필터·공개 상태·사용자 범위·결과 상한을 검증한 뒤 제한된 grant를 발급한다. 승인된 조회만 `PlaceQueryPort`가 수행한다.
4. FastAPI는 결과 및 claim/evidence proposal을 반환한다. Spring은 장소 공개·철회·소유권·허용 조건을 재검증하고 run 결과를 영속화한다.
5. Spring은 검증된 이벤트와 출처를 SSE로 전달한다. provider의 자유 생성 텍스트를 검증 전에 그대로 브라우저에 중계하지 않는다.
6. 실패 관측은 온라인 사용자 응답과 분리해 trace/평가 artifact로 축적한다. 결정적 탐지와 운영자 선택을 거친 경우에만 AI 품질 분석·격리 실험을 실행한다.

## 4. 백엔드 요구사항 및 완료 기준

### BE-01 공개 API·대화 지속성

- AI Engine의 [대화 API·저장 계약](https://github.com/YRootLab/onmaru-ai-engine/blob/main/docs/ai-rag/contracts-and-data.md)과 기존 Spring API/FE 계약을 대조한다.
- 사용자·conversation·turn·run 식별자, 이어하기, 재시도/idempotency, 삭제·보존, 비회원 정책을 Spring DB와 API schema에 반영한다.
- 대화 목록/상세/재개/저장(찜) API는 회원 소유권을 매 요청 확인한다. 기존 대화 저장 기간은 만료시키지 않는 제품 정책을 기본으로 하되, 회원 탈퇴·사용자 삭제·법적 요청 등 삭제 조건을 정의하고 실행한다.
- SSE event name, sequence, replay cursor, terminal event, heartbeat, reconnect 이후 snapshot 복구와 중복 제거를 fixture로 계약 검증한다.
- 완료 증거: OpenAPI/contract fixture, migration, 권한·idempotency·재접속 테스트, 기존 endpoint 호환 확인.

### BE-02 AI Engine 서비스 통합

- Spring→FastAPI는 private REST를 기준선으로 구현한다. gRPC는 실제 trace에서 해당 hop이 병목임을 확인한 뒤 동일한 업무·인가·timeout 조건으로 unary 실험을 한다. MCP는 고정 plan/execute 호출을 대체하지 않는다.
- FastAPI는 승인된 실행의 실제 진입 stage를 private `POST /internal/v1/ai/runs/{runId}/progress`로 Spring에 알린다. Spring은 활성 run 및 승인 `planHash`를 검증하고 기존 `run.stage` SSE에 반영한다. callback event schema·전용 token·중복/역순 처리·장애 격리는 [진행 callback 계약](fastapi-progress-callback.md)을 따른다.
- 공개 run/SSE 계약은 `SELECTING_EVIDENCE`, `GENERATING` 단계를 수용하도록 1.3으로 올린다. `RunSnapshot.sequence`는 마지막 commit된 public SSE sequence를 담고 Spring이 SSE ID와 함께 관리한다. Spring은 재연결 복원을 계속 소유하며, 최종 결과는 GET snapshot이 정본이다. FE가 1.2/1.3 및 unknown stage fallback을 지원하기 전 새 stage 발행은 활성화하지 않는다.
- 요청에는 contract version, run/conversation ID, trace context, 최소 필요 context, deadline을 포함한다. 회원 식별정보와 불필요한 개인정보는 제거/가명화한다.
- timeout, 4xx/5xx 분류, 제한 재시도, cancellation, circuit/degraded 응답을 정의한다. 재시도는 idempotency key와 같은 승인 grant를 사용하고 검색 전략을 임의 변경하지 않는다.
- AI Engine 장애 시 Spring은 명시적 degraded/failure 상태를 기록해 SSE를 정상 종료한다. 잘못된/출처 없는 응답을 성공으로 저장하지 않는다.
- 완료 증거: Spring↔FastAPI contract test, 인증 거부·timeout·재시도·downstream 장애 테스트, staging end-to-end trace.
- 추가 완료 증거: callback grant의 run/plan/scope/expiry 검증, ordinal 멱등·stale·terminal race, callback 실패 시 AI 결과 불변, 1.2/1.3 contract fixture 호환 검증.

### BE-03 정형 조회·권한 경계

- 정형 업무 조회는 `PlaceQueryPort` 같은 도메인 기반 application port로 제공한다. MCP/REST adapter는 이 port만 호출하고 repository/SQL을 직접 노출하지 않는다.
- 현재 결정은 MCP를 read-only, 제한 조회 용도의 선택적 inbound adapter로 두는 것이다. `readOnlyHint`는 권한 통제가 아니다. Spring Security의 service principal/scope, run-bound 짧은 grant, 고정 parameterized query, 필드 allowlist, 결과 상한을 적용한다.
- Online query principal과 quality harness principal을 분리한다. quality 도구는 비식별 run observation과 공개 source 상태만 읽는다. 업무 DB, 평가 DB 임의 SQL, 사용자 대화 전문 조회는 허용하지 않는다.
- grant는 단일 run·plan hash·허용 작업·field/filter·상한·만료에 결합한다. 재전송은 제한된 receipt로 멱등 처리하고 불일치 재사용을 거부한다.
- Spring AI MCP 구현은 ADR 승인 및 현재 Spring Boot와 MCP starter 호환·인증 동작 확인 뒤 추가한다. 실제 MCP가 불필요한 tool은 내부 REST/서비스 호출로 유지한다.
- 완료 증거: tool catalog/schema fixture 일치, scope·grant·비인가·만료·재사용 테스트, DB role/query plan 검증.

### BE-04 PostgreSQL 및 corpus 운영 경계

- PostgreSQL 운영 이미지는 기존 PostGIS를 보존하면서 선택한 pgvector 및 BM25 구현의 정확한 버전을 고정한다. BM25 확장은 한국어 tokenizer, 라이선스, 유지보수성, 메모리·디스크 비용 비교 후 확정한다.
- AI 전용 schema와 least-privilege runtime role을 분리한다. Spring 업무 role과 corpus writer/migrator 권한을 공유하지 않는다. migration은 일반 앱 기동에서 자동 실행하지 않는다.
- AI Engine corpus schema migration, embedding profile/dimension(384 목표), index/operator 호환성 및 corpus revision 전환은 AI 저장소와 버전 계약으로 맞춘다.
- 활성 corpus, rollback용 최근 1~2 revision, 원본/감사 보존·철회·GC를 [AI corpus lifecycle 계약](https://github.com/YRootLab/onmaru-ai-engine/blob/main/docs/ai-rag/corpus-lifecycle-and-gc.md)에 맞춰 운영한다. 삭제/철회 상태는 rollback으로 되살아나지 않게 한다.
- 운영 volume을 보존하는 backup/restore, 확장 설치, migration 전후 질의, 철회 재적용을 staging에서 검증한다.
- 완료 증거: pinned image digest, repeatable migration, PostGIS 회귀, vector/BM25 integration query, restore rehearsal, storage/index 용량 기록.

### BE-05 관측·로그·비용 신호

- Spring 및 FastAPI는 W3C Trace Context를 전파해 하나의 요청 trace를 구성한다. Spring ingress → auth/quota → AI HTTP client → AI stages → final validation → persistence/SSE 경로를 staging에서 확인한다.
- Spring은 사용자·업무 경계 span과 run observation을 소유하고 AI Engine은 내부 단계 span을 소유한다. span attribute는 route/stage/model/config version·문서 ID 개수·오류 code 등으로 제한하며 원문 질문, 답변 전문, token, secret을 metric label이나 기본 trace attribute에 넣지 않는다.
- 기존 Grafana/Prometheus/OTel 경로를 우선 사용한다. Prometheus는 metric backend이며 상세 trace 저장과 구분한다. Tempo/Loki/Langfuse 등 신규 상주 서비스는 데이터량·비용·메모리 근거 전에는 추가하지 않는다.
- 품질 review queue 및 주간 Discord/email 요약의 artifact는 AI Engine이 소유한다. Spring은 승인된 조회 projection만 제공하고, Grafana는 집계 대시보드와 review entry point를 제공한다.
- 완료 증거: traceparent 연속성, 지연/오류/사용량 metric, label cardinality 점검, 로그 redaction, collector drop·sampling 확인, dashboard/query 검증.

### BE-06 배포·운영

- 운영 인프라는 [repository-and-deployment.md](repository-and-deployment.md)에 따라 현재 Lightsail을 목표 2GB로 확장한 뒤 Spring·PostgreSQL/PostGIS/pgvector·Nginx·FastAPI를 한 host에서 공존시킨다. 새 서버 생성은 범위가 아니다.
- FastAPI는 private Docker network에만 연결하고 host port/Nginx public route를 열지 않는다. 서비스 credential은 서로 다른 audience/scope로 분리하고 `.env`/secret manager 외부에 기록하지 않는다.
- AI 저장소는 immutable image digest를 빌드·검사·발행한다. backend가 compose, deployer, health, secret, release pairing 및 rollback을 소유한다. 현재 비활성 profile은 운영 활성 상태가 아니다.
- 2GB에서 DB·Spring·FastAPI·모델·색인의 cold/warm·동시 부하, RSS, MemAvailable, swap/OOM, CPU, disk와 기존 API 회귀를 측정한다. Spring Blue-Green 중첩 상태도 포함한다.
- 완료 증거: staging 합격 기록, health/readiness, 외부 포트 점검, digest pairing, 실패 배포 rollback, DB backup/restore, 제한 SSH/deployer 검증. gate 미통과 시 운영 feature flag는 계속 꺼 둔다.

## 5. 실패 동작과 재시도 원칙

| 실패 | 백엔드 동작 |
|---|---|
| FastAPI connect/timeout | 상위 run deadline 내에서만 허용된 제한 재시도; 실패면 SSE terminal error/degraded 상태와 관측 기록 |
| Spring typed lookup 401/403/422/grant mismatch | 재시도 금지; 권한/계약 오류로 종료 및 감사 event |
| Spring typed lookup network/502/503 | 같은 opaque grant·idempotency receipt로 최대 1회 재시도; 승인되지 않은 새 검색 경로를 만들지 않음 |
| AI 응답 schema/version 불일치 | 최종 저장·SSE 확정 거부, 계약 오류 기록 |
| 공개/철회 검증 실패 또는 source revocation sequence 변경 | 해당 claim/run 결과 확정 거부; 철회 원본을 노출하지 않음 |
| OTel/collector 전송 장애 | 사용자 응답과 DB correctness는 관측 backend 가용성에 의존하지 않음; 필수 업무 감사는 Spring 저장소에 기록 |
| AI image health/readiness 실패 | 새 digest 활성화 중단 및 직전 digest 복구; Spring·DB 배포는 계속 별도 경로 |

## 6. 구현 순서와 게이트

1. **계약 고정:** AI repo의 API·schema·trace·claim/evidence·corpus version을 backend OpenAPI/DB/fixtures와 대조하고 호환성 검사를 CI에 연결한다.
2. **정형 backend 경계:** conversation/run 저장, rate limit/idempotency, typed `PlaceQueryPort`, 권한·grant, 공개/철회 최종 검증을 구현한다.
3. **온라인 연결:** private REST plan/execute, SSE replay, timeout/cancel/degraded 경로를 staging에서 검증한다.
4. **DB 준비:** PostGIS+pgvector+선택 BM25 image, schema migration, backup/restore와 revision/GC 전환을 검증한다.
5. **관측 연결:** W3C distributed trace와 low-cardinality metrics, 개인정보 redaction, Grafana dashboard를 검증한다.
6. **동일 host 배포:** 2GB 혼합 부하와 rollback gate를 통과한 뒤 수동 digest deploy와 feature switch를 연다.
7. **quality tools:** Spring read-only MCP tools가 필요하다고 ADR/통합시험에서 확인되면 별도 scope로 구현한다. 이 단계는 온라인 query 출시 자체의 선행조건이 아니다.
8. **프로토콜 최적화:** REST baseline 실측 뒤 필요할 때만 unary gRPC를 격리 비교하고, 근거가 없으면 REST를 유지한다.

## 7. 완료 정의

백엔드 통합은 아래 모두가 staging evidence로 확인될 때 완료다.

- contract/schema compatibility CI가 Spring과 AI Engine의 versioned artifact 조합을 검사한다.
- 회원 권한, quota, idempotency, 대화 이어하기, SSE reconnect, 최종 출처/공개 상태 검증이 통과한다.
- Spring→FastAPI→Spring의 trace ID와 span 관계가 유지된다.
- AI 및 typed lookup의 timeout·권한 오류·부분 실패가 정해진 실패 동작으로 재현된다.
- PostgreSQL extension, corpus migration, vector/BM25 query, backup/restore 및 revocation regression이 통과한다.
- 2GB 단일 Lightsail host 공존·배포·rollback 검증을 통과하고 공개 FastAPI/PostgreSQL 포트가 없다.
- 운영 활성화는 위 gate 및 기존 배포 절차가 끝난 뒤 별도 수동 설정으로만 가능하다.

## 8. AI Engine 문서의 구현 의미

`onmaru-ai-engine/docs/ai-rag/`는 AI 저장소가 구현할 목표 명세의 정본이다. 다만 문서에 기술된 구조가 이미 코드에 존재한다는 뜻은 아니며, 설계 전부를 한 번에 운영 활성화하라는 뜻도 아니다. AI 저장소는 PRD의 인수 기준과 architecture/module, query processing, data/schema/lifecycle, evaluation, grounding, observability, quality harness, release 문서를 모듈별 구현 계획으로 분해해야 한다. 각 항목을 코드·migration·테스트·측정 증거로 닫고, unresolved/production readiness gap은 먼저 결정하거나 명시적 gate로 남긴다.

특히 첫 온라인 출시의 SQL·BM25·dense vector·hybrid 경로 요구와 AI 저장소의 실제 baseline 상태를 구분한다. 기존 baseline을 목표 완료로 간주하지 않는다. 외부 Wiki/API 검색은 권리·신선도·source revocation 계약이 확정되기 전까지 온라인 범위에서 제외한다. 품질 감독은 deterministic detection과 운영자 선택 흐름부터 구현하고, LLM/Antigravity CLI 분석은 비용 승인·격리 실험·사람 승인이 갖춰지기 전 자동 실행하지 않는다.

## 9. 관련 정본

- AI 제품·RAG 명세: [AI Engine `docs/ai-rag/`](https://github.com/YRootLab/onmaru-ai-engine/tree/main/docs/ai-rag)
- AI 구현 준비도 및 미확정 사항: [Production readiness gaps](https://github.com/YRootLab/onmaru-ai-engine/blob/main/docs/ai-rag/production-readiness-gaps.md)
- Spring 내부 tool 경계: [journey-query-mcp.md](journey-query-mcp.md), [quality-tools-mcp.md](quality-tools-mcp.md)
- 배포와 공존: [repository-and-deployment.md](repository-and-deployment.md), [Lightsail README](../../infra/lightsail/README.md)
- 공유 wire 계약 및 fixtures: [docs/contracts/](../contracts/)
- PostgreSQL 스키마 정본: [docs/database/schema.md](../database/schema.md)
- 결정 기록: [ADR-0017](../decisions/0017-home-journey-hybrid-rag.md), [ADR-0018](../decisions/0018-quality-harness-spring-mcp-tools.md), [ADR-0019](../decisions/0019-journey-query-mcp-tools.md)
