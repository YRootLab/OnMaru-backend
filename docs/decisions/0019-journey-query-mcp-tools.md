---
id: ADR-0019
title: Spring PlaceQueryPort에 정형 조회 adapter를 둔다
status: proposed
date: 2026-10-22
locale: ko
decision_makers:
  - 사용자 검토 필요
related:
  - ADR-0002
  - ADR-0005
  - ADR-0017
  - ADR-0018
affected_paths:
  - docs/ai-rag/
  - docs/ai-rag-spring/
  - docs/contracts/mcp/
tags:
  - ai
  - mcp
  - journey
  - data-ownership
retrospective: false
---

# Spring PlaceQueryPort에 정형 조회 adapter를 둔다

## 상태와 맥락

상태는 제안이다. 정형 조회 use case와 Spring AI MCP tool/FastAPI MCP client는 아직 구현되지 않았다. 현재 설계에서는 Search Router가 질문을 `SQL_ONLY`/`SQL_PLUS_RETRIEVAL`/본문 검색 route로 분기한다. Spring은 회원·정형 업무 DB·공개/철회 권한을 소유하고, FastAPI는 질문 처리·BM25/vector/RRF/reranker·근거 기반 생성을 소유한다.

FastAPI가 정형 필드가 필요할 때 Spring의 canonical query service를 써야 하지만 업무 DB에 직접 접속하거나 SQL 문자열을 주고받아서는 안 된다. transport와 무관한 typed application port를 두고, 필요할 때 REST 또는 MCP inbound adapter를 연결한다. MCP 도입이 tool calling loop나 LLM 기반 route 선택을 뜻하지 않도록 한다.

## 제안 결정

- Spring application input port `PlaceQueryPort`는 grant-bound 공개 장소 lookup만 제공한다. application service가 권한 검증 후 고정 parameterized repository query를 실행한다. MCP adapter는 port에 위임하며 SQL·Repository 접근을 하지 않는다.
- Spring↔FastAPI plan/execute 통신은 private REST를 기준선으로 구현·계측한다. 이 hop이 실제 병목으로 확인된 경우 동일한 typed contract의 gRPC unary를 우선 성능 비교하고, 이득이 입증될 때만 채택한다. 이는 bidirectional streaming 도입 결정이 아니다. `PlaceQueryPort`는 별도 정형 조회 경계이며, 그 transport 비교는 별도 실험으로 둔다. 여러 AI client/agent가 capability를 발견·재사용해야 하면 Spring AI MCP adapter를 tool surface로 사용한다. FastAPI `ApprovedPlanExecutor`는 결정적 typed invocation을 하며 online LLM tool loop를 실행하지 않는다.
- Spring은 plan validation 후 30초 opaque `queryGrantId`를 발급하고 grant를 특정 run, plan hash, allowed canonical field, filters, 공개 권한 및 최대 5개 결과에 결합한다. selected adapter에는 grant만 전달하며 grant-bound 실행은 멱등하다.
- SQL 문자열, 테이블·필드 임의 선택, 자유 filter, URL, 사용자 ID, 대화 이력은 tool 입력으로 받지 않는다. query는 Spring의 고정 parameterized template/service에서만 실행한다.
- `SQL_ONLY`는 typed lookup 결과를 deterministic renderer로 반환하며 LLM/embedding/retrieval 호출은 0회다. `SQL_PLUS_RETRIEVAL`은 Spring typed lookup과 AI 내부 retrieval을 각각 실행해 검증된 결과만 합친다. 검색 전용 route는 Spring lookup 호출 0회다.
- Online `journey.query.execute`와 오프라인 quality `quality.read.*`는 별도 principal, audience, scope, endpoint surface로 격리한다. Quality investigation에는 기존 read-only tool 2개만 제공한다.
- plan/execute REST endpoint는 control-plane orchestration으로 유지한다. MCP는 capability discovery·재사용 이점이 실측/운영 요구에서 확인된 tool access 표면에 한해 쓰며 일반 API·회원 SSE를 대체하지 않는다.
- MCP 채택 시 공유 tool schema는 [journey-query-tools.v1.json](../contracts/mcp/journey-query-tools.v1.json)에 둔다. `PlaceQueryPort` 계약과 transport 선택은 [Spring 정형 조회 설계](../ai-rag-spring/journey-query-mcp.md)에 둔다.

## 근거

Spring AI MCP server annotations는 `@McpTool`로 MCP tools를 등록하고 parameter schema를 자동 생성할 수 있다. 현재 저장소의 Spring Boot 4.1.1/Spring MVC와 맞는 구현 후보는 Spring AI 2.0.1 및 `spring-ai-starter-mcp-server-webmvc`다. Spring AI 2.0.x가 Boot 4.1.x를 지원한다고 안내하지만 실제 security/transport/catalog 호환은 implementation test로 입증한다. FastAPI Python MCP client는 MCP `tools/call`로 해당 tool을 실행한다. 이 연결은 Spring AI server와 Python client 간 프로토콜 호출일 뿐 Spring AI가 질문 해석이나 LLM tool selection을 맡는다는 뜻은 아니다.

MCP가 아니라 Spring application layer가 grant와 권한을 검증한다. FastAPI에는 Spring 업무 DB 계정이 없다. transport와 무관한 `PlaceQueryPort`를 유지하면 private REST와 MCP를 같은 업무 로직에 연결해 비교할 수 있고, 필요하면 MCP를 재사용 가능한 tool discovery surface로 추가할 수 있다.

## 대안

| 대안 | 평가 |
|---|---|
| FastAPI에서 Spring 업무 PostgreSQL에 직접 query | 거부. DB 권한·스키마 결합·회원 데이터 노출 위험이 생긴다. |
| 기존 private REST endpoint로 정형 facts 조회 | 유효한 기본 대안. 고정 단일 function이면 운영 복잡도와 protocol round trip이 적은 REST가 적합할 수 있다. 동일한 application port 뒤에서 MCP와 latency·error·schema 운영성을 비교한다. |
| MCP tool이 기존 REST보다 빠르거나 안전하다는 가정 | 거부. MCP는 tool discovery/interoperability 이점이 있을 수 있다. 고정된 단일 호출은 private REST를 기준선으로 삼고, 같은 `PlaceQueryPort`에서 성능·운영성을 비교한다. |
| gRPC가 항상 더 빠르다는 가정 | 거부. unary gRPC를 동일한 업무 로직·인가·DB 조건으로 측정하고, latency·CPU·payload와 proto/codegen·관측성 운영 비용을 함께 평가한다. |
| MCP tool에 SQL/filter를 받아 실행 | 거부. 입력이 권한 범위를 정하고 SQL injection/권한 확대 표면이 된다. |
| LLM이 모든 route/tool을 선택 | 거부. query routing은 결정적 정책이며 같은 입력의 재현성·비용·trace 분석을 보장해야 한다. |
| Query API·quality 도구를 한 principal로 통합 | 거부. 온라인 요청 credential에 오프라인 조사 권한을 결합한다. |

## 결과와 검증

`PlaceQueryPort`는 전송 방식에 관계없이 application boundary로 유지한다. MCP 채택 시 Spring AI MCP server/client dependency와 version matrix, Python MCP client, JSON schema/catalog 관리가 추가된다. gRPC benchmark에는 proto schema·generated code·Java/Python client server 및 telemetry context propagation이 필요하다. 두 adapter 모두 동일한 인증·인가·timeout·trace 정책으로 staging 비교하며, `readOnlyHint`는 advisory metadata일 뿐 권한을 강제하지 않는다.

운영 기본 경로는 Spring↔FastAPI private REST다. MCP는 tool discovery 요구가 있을 때 별도 tool adapter로 사용할 수 있다. gRPC 실험은 먼저 plan/execute hop이 trace에서 병목인지 확인한 뒤 같은 contract의 unary RPC를 staging에서 비교한다. p50/p95/p99·처리량·오류·payload·CPU/RSS·직렬화/DB 시간, trace 연결·권한 실패 0건·응답 의미 동등성을 확인한다. 개선이 계측 오차를 넘고 codegen·배포·관측 비용을 상쇄하지 못하면 REST를 유지한다. `PlaceQueryPort` 조회 경계는 별도 비교 대상이며, 2GB mixed-load도 확인한다.
