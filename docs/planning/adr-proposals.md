# 장기 의사결정 검토 초안

> **현재 설계 기준:** [문서 안내](../README.md)의 책임별 설계를 따른다. 이 문서는 정식 ADR 전의 과거 검토안이며, 구현 완료를 뜻하지 않는다.

> **ARCHIVED:** 도슨트 Q&A/RAG 관련 과거 후보는 정식 ADR 등록 대상으로 사용하지 않는다. 현재 구조 초안은 [foundation architecture drafts](../decisions/drafts/foundation-architecture.md)를 따른다.

2026-09-09 후속 검토: [P0/ADR 검토표](p0-triage.md). 사용자 확정 런타임 역할만 [ADR-0002](../decisions/0002-spring-business-fastapi-ai.md)에 기록했다. D1~D4 및 E1~E5의 구체적 구조는 제안 상태를 유지한다.

2026-09-09. 이 파일은 보고서의 선택안이며 정식 ADR/Accepted 결정이 아니다. `adr-toolkit preflight`에서 기존 `docs/decisions`를 확인했고 `related` 조회에서 관련 ADR은 없었다. ADR-0001은 결정 기록 절차만 정의한다.

사용자 확정 사실은 Java/Spring Boot, Python/FastAPI, 관계형 DB 중심, 이슈 기반 개발, core의 외부 기술 독립 목표다. 인원·hosting·auth·LLM 모델은 미정이다.

## D1. 순수 코어와 선택적 Hexagonal 모듈 경계

| 항목 | 검토안 |
|---|---|
| 제목 / slug | Spring 비즈니스 서버의 순수 코어와 모듈 경계를 정한다 / `spring-modular-core-boundaries` |
| 문제 | 관광·DB·AI SDK 의존성이 업무 정책에 섞이면 교체와 단위 테스트가 어려움 |
| 대안 A | 단일 Boot feature package: 빠른 시작, framework classpath 노출 |
| 대안 B | domain/application core 모듈 + 기술 adapter 모듈: 코어 보호와 적당한 파일 수 |
| 대안 C | 모든 feature-layer를 Gradle 모듈화: 강한 격리, 많은 조립 비용 |
| 제안 | B. 한 Spring deployable, JDK-only core, app transaction wrapper, port 기반 adapter |
| 주된 이유 | 사용자의 외부 기술 독립성과 테스트 목표를 컴파일 경계로 검증 |
| 받아들이는 비용 | mapper/wrapper/bridge 증가, 단일 persistence의 package 규칙 필요 |
| 영향 경로 | `apps/spring-api`, `modules`, `adapters`, `settings.gradle.kts`, `.github/workflows/ci.yml` |
| 검증 | 금지 import, context internal 접근, cycle fixture가 CI 실패 |
| 재검토 | context adapter의 반복된 내부 침범, 독립 팀·배포의 실질 필요 |

## D2. PostgreSQL 공간 데이터와 AI 저장 소유권

| 항목 | 검토안 |
|---|---|
| 제목 / slug | 공간 데이터와 AI 검색 저장소의 쓰기 소유권을 분리한다 / `postgresql-spatial-ai-ownership` |
| 문제 | 공간 쿼리·관계 제약·RAG를 지원하며 초기 운영 시스템 수를 제한해야 함 |
| 대안 A | PostgreSQL/PostGIS + AI 단계에서 같은 인스턴스의 pgvector schema |
| 대안 B | MySQL + 별도 vector DB: 팀의 MySQL 경험이 강하면 가치가 있으나 현재 근거 없음 |
| 대안 C | PostgreSQL/PostGIS + 독립 vector DB: 강한 격리, 운영 대상 증가 |
| 제안 | A. Python은 AI schema만 쓰고 Spring은 business schema만 씀 |
| 주된 이유 | 반경 장소 검색과 원본/후기 정합성을 한 관계형 DB에서 검증 |
| 받아들이는 비용 | PostGIS/pgvector SQL 종속과 공유 자원 경쟁, engine 교체 시 migration 재작업 |
| 영향 경로 | `adapters/persistence-jpa`, `apps/ai-api/migrations`, `infra`, `docs/database` |
| 검증 | extension container의 제약·공간 query, AI role의 business write 거부, restore |
| 재검토 | 제한/최적화 후에도 AI DB 부하가 핵심 API SLO를 침해 |

## D3. Canonical ID와 관측 데이터 의미

| 항목 | 검토안 |
|---|---|
| 제목 / slug | 내부 식별자와 원본 데이터 의미를 분리한다 / `canonical-identity-source-provenance` |
| 문제 | FE/초기 기획에 UUID/contentId/stid 혼용, 지역 방문자와 장소 인원의 혼합 위험 |
| 대안 A | contentId를 모든 장소 PK로 사용: 빠르지만 다른 source에 취약 |
| 대안 B | 내부 UUID + namespace source key, 명시 mapping/revision/관측 metadata |
| 대안 C | FE별 모델 독립: 이관은 적지만 장소 연결과 공유 ownership 복잡 |
| 제안 | B. Odii 언어별 복합 원본 ID 보존, 지역 count를 장소 인원으로 변환하지 않음 |
| 주된 이유 | 서로 다른 출처·시점의 데이터를 같은 장소의 사실로 오인하지 않게 함 |
| 받아들이는 비용 | FE ID 이행, 매핑 테이블, unresolved 상태, source qualification |
| 영향 경로 | `contracts/openapi`, `modules/catalog`, `modules/audio`, `modules/insights`, `docs/database` |
| 검증 | namespace 충돌, 같은 stid 다른 stlid, 결측/지연 관측, legacy ID fixture |
| 재검토 | 공급자 ID 동등성이 공식 확인되거나 public contract major 변경 |

## D4. 검증 대본에 한정한 FastAPI RAG

| 항목 | 검토안 |
|---|---|
| 제목 / slug | AI 도슨트의 근거·실패·비용을 내부 계약으로 격리한다 / `fastapi-docent-grounding-contract` |
| 문제 | FE scriptContext 신뢰는 근거 조작을 허용하고 AI 실패는 core 가용성·비용을 침해 |
| 대안 A | Spring에서 provider 직접 호출: 단일 서비스이나 Python 선택과 별개 구현 필요 |
| 대안 B | FastAPI 내부 HTTP, Spring이 source revision 결정, Python은 retrieval/생성 |
| 대안 C | broker 기반 비동기 답변 job: 긴 생성에 적합하지만 현재 UX와 운영 비용 증가 |
| 제안 | B. JSON 먼저, 필요 시 fetch SSE. 생성 자동 retry 없음, 출처·usage·오류를 port에 보존 |
| 주된 이유 | Python AI 구현과 장애를 비즈니스 정책에서 분리 |
| 받아들이는 비용 | 두 deployable 계약·revision 동기화, outbox/cancel/deadline 테스트 |
| 영향 경로 | `modules/docent`, `adapters/ai-fastapi`, `apps/ai-api`, `contracts/openapi`, `apps/spring-api` |
| 검증 | 허용 revision 검색, abstention, timeout/cancel/예산, AI 종료 중 catalog 조회 |
| 재검토 | 실측 latency 목표 미달, 장시간 job 요구, 직접 provider 구현의 비용 우위 확인 |

## 중요도 검증 결과

점수는 순서대로 reversal/alternatives/quality/boundary/multi-developer/ops-data/future-rationale이다. 구현 전이라 reversal은1, 모두 세 대안을 검토했고 이후 사람·에이전트가 따라야 하는 구조이므로 alternatives/boundary/multi-developer는2다. D1 운영 영향은 간접적이어서1, DB/식별자/AI 계약은 직접 영향을 주므로2다.

| 후보 | 7개 점수 | CLI total | 분류 |
|---|---|---:|---|
| D1 | 1,2,2,2,2,1,2 | 12 | recommended |
| D2 | 1,2,2,2,2,2,2 | 13 | recommended |
| D3 | 1,2,2,2,2,2,2 | 13 | recommended |
| D4 | 1,2,2,2,2,2,2 | 13 | recommended |

입력은 `adr-scores/d1.json`~`d4.json`, 검증은 `adr.py significance --input <file> --json`이다. 점수는 기록 중요도이지 선택의 정답 점수가 아니다.

## 정식 기록으로의 전환

승인된 후보는 full MADR draft JSON으로 작성한 뒤 `adr.py create --input <draft.json> --slug <slug> --dir docs/decisions --json`으로 등록한다. 번호/상태/링크를 수동 편집하지 않는다. scaffold 경로 확정 후 기계 검증 가능한 Implementation Constraints를 포함하고 validate/index를 실행한다. Accepted 전환은 별도 lifecycle preview로 확인한다.

ADR Toolkit [SKILL.md](/Users/yangseunghyeon/.agents/skills/adr-toolkit/SKILL.md)의 RECORD CONFIRM은 “Get explicit approval of the draft.”를 요구한다. 이번 단계는 우선 브레인스토밍 보고서 작성이므로 제목·대안·결정·비용·slug를 검토 가능한 형태로 제시했다. 정식 등록 전에 이 구체적인 선택안을 확인한다.

인증 제공자·모델·hosting·H3 알고리즘은 아직 선정하지 않았다. 근거를 만들어 Accepted ADR로 기록하지 않는다.
