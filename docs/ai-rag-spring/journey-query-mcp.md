# 여정 정형 조회용 Spring port와 transport adapter

> 상태: 설계 제안, 구현 전. Spring `PlaceQueryPort`가 승인된 공개 장소 정형 조회를 소유한다. Spring↔FastAPI의 plan/execute 통신은 private REST 기준선을 유지하고, 실제 병목이 확인되면 unary gRPC를 비교한다. MCP는 agent tool discovery용이다. 이 설계는 DB 조회 API로 임의 SQL을 노출하거나 Spring MCP 서버가 LLM을 호출한다는 뜻이 아니다.

## 목적과 적용 범위

AI Engine의 Query Processing Layer는 질문을 정규화하고 `Search Router`가 결정적으로 경로를 제안한다. Spring↔FastAPI의 plan/execute는 고정된 서비스 간 작업 계약이므로 private REST를 우선 구현한다. 운영 trace에서 이 hop의 지연이 유의미한 병목으로 확인될 때 동일 계약의 unary gRPC를 실험한다. 정형 장소 데이터가 필요한 `SQL_ONLY` 또는 `SQL_PLUS_RETRIEVAL`에서 AI Engine은 Spring `PlaceQueryPort` 기능을 별도 private adapter로 호출한다. REST가 기본 전송, MCP는 도구 발견·재사용을 위한 선택지다. BM25·pgvector·RRF·reranker는 AI Engine 내부 검색 코드로 남고 Spring tool/API에 노출하지 않는다.

MCP는 여기서 Spring의 기존 애플리케이션 기능에 접근하는 **typed tool boundary** 후보다. 요청 흐름·제품 API·회원 SSE는 기존 Spring REST/SSE를 유지한다. Spring의 plan proposal endpoint도 서비스 간 orchestration 계약이므로 유지한다. 따라서 MCP는 모든 HTTP 호출의 대체물이 아니다. 특히 Spring이 MCP 호출 중 LLM을 실행하거나, AI Engine이 사용자 문장만으로 SQL을 작성해 전달하는 구조는 금지한다.

정형 조회의 권한 경계는 MCP handler가 아니라 Spring application layer다. `PlaceQueryPort`는 허용된 장소 조회 use case만 정의하는 typed application port이고, MCP inbound adapter는 이 port에 위임한다. application service가 grant·공개 상태·허용 필드·결과 상한을 검증하고 기존 repository가 고정 parameterized query를 실행한다. DB 계정과 DB 제약도 Spring이 소유한다. MCP method에서 SQL, 범용 query DSL, repository를 직접 노출하지 않는다. `readOnlyHint=true`는 client를 위한 동작 설명일 뿐 권한이나 DB read-only를 강제하지 않는다.

MCP adapter를 선택하면 Spring AI의 `@McpTool`/`@McpToolParam`으로 tool 함수와 JSON Schema를 선언하고, 구현체는 application port를 호출한다. 반환 DTO에는 output schema를 명시하거나 생성한다. `@Tool`은 Spring 내부 `ChatClient`의 function/tool calling용이므로 MCP server 노출과 혼동하지 않는다. 온라인 REST/gRPC 호출과 MCP tool invocation은 모두 결정적 `ApprovedPlanExecutor`가 수행하고, 온라인 LLM에 자유 tool selection loop를 주지 않는다. [Spring AI MCP Server annotation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html), [Spring AI tool calling](https://docs.spring.io/spring-ai/reference/api/tools.html).

현재 저장소는 Spring Boot `4.1.1`과 Spring MVC를 사용한다. 설계 기준 조합은 Spring AI `2.0.1` + `spring-ai-starter-mcp-server-webmvc`다. Spring AI 2.0.x는 Boot 4.0.x/4.1.x를 지원한다고 안내하고, 현재 안정판 문서는 2.0.1을 가리킨다. 구현 PR에서 Maven Central BOM·보안 자동설정·Streamable HTTP endpoint·generated schema를 실제 build/integration test로 다시 고정한다. [Spring AI version compatibility](https://docs.spring.io/spring-ai/reference/getting-started.html), [Spring AI 2.0.1 MCP annotation](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html).

## v1 tool catalog

| MCP tool | caller | 입력 | 출력/효과 | 허용 조건 |
|---|---|---|---|---|
| `journey_execute_approved_lookup` | FastAPI `ApprovedPlanExecutor` | Spring이 발행한 `queryGrantId` 하나 | 승인된 계획에 따른 공개 장소 최대 5건의 정형 projection. 읽기 전용·멱등 | plan route가 `SQL_ONLY` 또는 `SQL_PLUS_RETRIEVAL`의 정형 조회를 포함하고 grant가 유효할 때 |
| `quality_get_run_observation` | 온디맨드 quality harness | 불투명 `runId` | 비식별 실행 관측 projection | 별도 quality principal 및 `quality.read.run` |
| `quality_get_public_source_state` | 온디맨드 quality harness | `sourceId`, `sourceRevision` | 공개·철회·라이선스 상태 code | 별도 quality principal 및 `quality.read.source` |

공유 schema는 [journey query catalog](../contracts/mcp/journey-query-tools.v1.json)와 기존 [quality catalog](../contracts/mcp/quality-tools.v1.json)에 둔다. Spring AI가 schema 생성의 구현 수단이어도 versioned JSON catalog는 cross-repository 호환성 fixture로 유지한다. tool schema/annotation은 advisory metadata이고 인가를 대신하지 않는다.

Spring 측 method 모양은 아래처럼 얇게 유지한다. 이 코드는 설계 예시이며 implementation PR에서 Spring AI 2.0.1의 실제 import/API와 generated schema를 검증한다.

```java
// Application input port: 허용된 기능만 표현하며 SQL/Repository 계약이 아니다.
public interface PlaceQueryPort {
    PlaceLookupResult executeApprovedLookup(QueryGrantId grantId);
}

record QueryGrantId(String value) {}

@Component
final class JourneyQueryMcpTools { // MCP inbound adapter
    private final PlaceQueryPort placeQuery;

    JourneyQueryMcpTools(PlaceQueryPort placeQuery) {
        this.placeQuery = placeQuery;
    }

    @McpTool(
        name = "journey_execute_approved_lookup",
        description = "Execute one Spring-approved public place lookup",
        generateOutputSchema = true,
        annotations = @McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = false
        )
    )
    PlaceLookupResult executeApprovedLookup(
        @McpToolParam(description = "Opaque Spring-issued query grant", required = true)
        String queryGrantId
    ) {
        return placeQuery.executeApprovedLookup(new QueryGrantId(queryGrantId));
    }
}
```

`PlaceQueryPort` 구현체인 `ApprovedJourneyLookupService`가 grant를 원자적으로 확인하고 조회/receipt를 처리한다. 그 아래 repository는 승인된 query template만 parameter binding으로 실행하고 결과 상한을 강제한다. DB에는 가능하면 공개 projection view에 한정된 `SELECT` 전용 role을 사용한다. 기존 datasource credential이 쓰기 권한도 갖는다면 `@Transactional(readOnly = true)`만으로 이를 대체할 수 없으므로, MCP read path에 별도 최소 권한 datasource/role을 둘지 구현 전에 확정한다. 이 wrapper에서 ChatClient·LLM 호출, repository 직접 접근, SQL 조립은 하지 않는다. 실제 Java 접근 제한자·생성자 주입 방식은 프로젝트 코드 규약에 맞춘다.

## 전송 선택과 성능 비교 기준

MCP가 일반 HTTP API보다 본질적으로 빠르거나 안전한 것은 아니다. Streamable HTTP를 사용하면 MCP도 HTTP 위에서 JSON-RPC를 주고받으며, schema/discovery와 protocol 처리 비용이 더해질 수 있다. 반대로 MCP는 여러 MCP client/agent에 동일한 tool catalog·입출력 schema·호출 규약을 제공할 때 상호운용성과 발견 가능성을 준다. Spring AI의 `@McpTool`은 이 tool 등록과 schema 생성을 돕지만 성능 최적화를 보장하지 않는다. [MCP tools](https://modelcontextprotocol.io/specification/2025-11-25/server/tools), [Spring AI MCP annotations](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-annotations-server.html).

따라서 이 여정 조회는 결정적 Search Router가 이름을 알고 정확히 한 번 호출하는 고정 작업이다. 동적 tool discovery가 실제 요구가 아니라면 private REST endpoint가 더 단순하다. MCP tool catalog를 발견하고 선택하는 기능이 핵심인 온디맨드 quality harness에는 MCP가 더 자연스러울 수 있지만, 사용자 온라인 Search Router는 결정성을 위해 고정 route-to-function mapping을 유지한다.

| 호출 표면 | 첫 설계 권고 | MCP가 유리해지는 조건 |
|---|---|---|
| 사용자 API·SSE 및 Spring↔FastAPI plan/execute | 기존 REST | MCP tool protocol은 제품 API/SSE나 고정 orchestration RPC의 대체 목적이 아님 |
| Spring↔FastAPI의 고정 plan/execute 작업 | REST로 구현·계측; hop 병목이 확인되면 gRPC unary를 동일 계약으로 비교 | 동적 tool discovery 대상이 아니므로 MCP는 사용하지 않음 |
| 온라인 `PlaceQueryPort` 정형 lookup | private REST adapter를 기준선으로 둠 | 다른 AI client가 동일 tool을 발견·재사용하면 MCP; 이 개별 lookup hop이 병목으로 측정되면 별도 unary gRPC 비교 가능 |
| 온디맨드 quality harness의 Spring 관측 도구 | 좁은 MCP tools를 활용할 수 있음 | agent가 허용된 도구를 검색해 조사 순서에 맞게 호출할 필요가 있을 때. 항상 allowlist·scope 검증 유지 |
| BM25·vector·RRF·reranker 및 AI 소유 평가 artifact | FastAPI 내부 typed Python component/function | 별도 서비스 소유권·재사용 경계가 생기는 경우에만 네트워크 tool화 검토 |

안정된 typed contract 뒤에서 transport를 교체 가능하게 둔다. 우선 측정할 gRPC 후보는 Spring↔FastAPI plan/execute unary다. 이는 양방향 streaming이 아니라 고정 입력·출력의 내부 요청/응답이며, HTTP/2 channel 재사용과 Protocol Buffers의 실익을 검증한다. 별도 `PlaceQueryPort` lookup hop은 필요성이 관측될 때만 후속 비교한다. REST를 운영 기준선으로 측정하고, MCP는 tool discovery의 실익이 있는 표면에 적용한다. gRPC는 실제 병목 확인 및 staging 비교 전 production dependency로 채택하지 않는다. 실제 이득은 payload·호출 빈도·연결 재사용·DB 처리시간에 따라 달라진다. [gRPC performance best practices](https://grpc.io/docs/guides/performance/), [gRPC benchmarking](https://grpc.io/docs/guides/benchmarking/).

### 전송 비교 계획

첫 비교는 Spring↔FastAPI plan/execute의 동일한 versioned request/response contract, 인증, 업무 실행, timeout, 재시도 정책을 사용하고 transport만 바꾼다. `PlaceQueryPort` lookup의 REST/MCP/gRPC 비교는 별도 실험으로 분리한다.

| 비교 항목 | REST 기준선 | MCP 비교 | gRPC 비교 |
|---|---|---|---|
| 호출 형태 | Spring↔FastAPI private HTTP endpoint, JSON DTO | orchestration에는 해당 없음; MCP는 별도 tool call 비교 | plan/execute unary RPC; `.proto`와 generated Java/Python client/server 사용 |
| 공통 통제 | 동일 TLS·인증 audience/scope·요청·업무 로직·timeout·retry | 같은 업무 경계에서 별도 측정 시 동일 정책 | 동일 인증·인가·업무 로직·timeout·retry, W3C trace context metadata 전파 |
| 측정 | warm/cold latency, p50/p95/p99, 처리량, 오류, payload bytes, client/server CPU·RSS, DB 시간, end-to-end trace | REST와 같은 지표 및 tools/list 초기 비용 | REST와 같은 지표, channel 재사용 조건과 codegen/운영 비용 |
| 도입 판단 | 운영 기본값 | tool discovery/interoperability가 실제 요구를 줄이는지 | 안정적으로 반복되는 성능 개선이 추가 proto/codegen/운영 복잡도를 상쇄하는지 |

먼저 낮은 동시성과 현실적인 동시성에서 기준 분포를 수집하고, 개선 기준은 측정 오차와 업무상 이득을 검토한 뒤 정한다. 응답 정확도·인가 실패 0건·trace 연결·재시도 의미는 동등해야 한다. gRPC가 유의미한 end-to-end 개선을 보이지 않으면 REST를 유지한다. 검색 내부의 BM25·vector·RRF·reranker는 호출 비용과 장애 경계를 늘리지 않도록 계속 FastAPI 내부 component로 둔다. 사용자 브라우저의 SSE도 기존 Spring REST/SSE가 소유한다.

### HTTP/2 멀티플렉싱과 gRPC streaming은 다른 기능

HTTP/2는 하나의 TCP connection 안에 여러 HTTP stream을 열어 각 요청/응답의 frame을 섞어 전송한다. 연결이 이미 살아 있다면 각 요청 stream마다 TCP handshake를 다시 하지 않는다. 여기서 stream은 HTTP 요청/응답 한 건의 전송 단위다. 여러 독립 조회를 동시에 보낼 때 REST over HTTP/2도 이 multiplexing 이점을 얻을 수 있다. 다만 HTTP/2 stream들은 하나의 TCP byte stream을 공유하므로 TCP packet loss가 발생하면 재전송 동안 뒤의 stream frame도 기다릴 수 있다. Multiplexing은 연결 비용과 동시성에 도움을 주지만 네트워크 손실이나 서버 처리 시간을 없애지는 않는다. [RFC 9113](https://datatracker.ietf.org/doc/html/rfc9113).

gRPC unary도 HTTP/2 위의 요청/응답 한 건이고, 보통 재사용 channel/stub을 통해 여러 unary RPC가 같은 HTTP/2 connection에서 multiplex될 수 있다. 따라서 “요청이 여러 번이면 반드시 gRPC bidi가 더 빠르다”는 결론은 나오지 않는다. REST client가 HTTP/2 connection pool을 재사용하는 조건과 gRPC channel 재사용 조건을 맞춰 비교해야 한다. 동시 RPC 수가 server/connection의 허용 stream 한도를 넘으면 queueing이 생길 수 있으며, 장수명 streaming RPC가 stream slot을 오래 점유할 수도 있다.

gRPC bidirectional streaming은 **클라이언트가 시작한 하나의 장수명 RPC/HTTP2 stream 안에서** client와 server가 여러 message를 각각 보낼 수 있는 RPC 계약이다. 양쪽 message 흐름은 독립이며 각각 순서가 보장된다. 이는 서로 다른 여러 RPC를 multiplex하는 HTTP/2 기능과 다르다. 긴 상호작용을 여러 번 왕복할 때 RPC를 새로 열고 끝내는 비용을 줄일 수 있지만, HTTP/2 connection 자체를 gRPC bidi만 사용할 수 있는 것은 아니다. gRPC 공식 문서도 streaming은 반복적인 RPC initiation을 피할 수 있지만, stream의 부하분산·오류 복구·운영 복잡성을 함께 평가하라고 안내한다. [gRPC RPC types](https://grpc.io/docs/what-is-grpc/core-concepts/), [gRPC performance guidance](https://grpc.io/docs/guides/performance/).

HTTP/2의 stream 자체도 frame 수준에서 양방향 데이터가 오간다. 다만 전형적인 REST API의 애플리케이션 계약은 유한한 요청을 보내고 응답을 받는 request/response다. 응답 body를 열어 둔 채 server가 SSE/chunked response를 보낼 수 있지만, 응답이 끝난 다음 서버가 임의로 새 메시지를 보내는 연결은 아니다. HTTP/2 server push도 애플리케이션의 지속적인 양방향 대화와 같지 않다. 즉 “REST는 절대로 server→client 전송 불가”가 아니라, SSE는 server→client 단방향 전달, WebSocket이나 gRPC bidi는 지속적인 양방향 메시지 교환에 맞는 모델이다.

OnMaru의 현재 요청은 사용자 질문 1건을 받아 FastAPI가 계획·검색·생성을 마친 뒤 Spring에 결과를 전달하는 흐름이다. 정형 장소 조회는 한 번의 unary lookup으로 충분하며 gRPC bidi를 쓸 대화형 peer loop가 없다. 답변/상태를 브라우저에 전달하는 경로는 Spring 소유 SSE로 남는다. 나중에 FastAPI가 Spring에 여러 단계의 검증된 event를 지속 전송할 요구가 생기면 우선 gRPC server-streaming을 검토하고, Spring도 같은 활성 호출 중에 반대 방향 message를 보내야 하는 실제 제어 흐름이 있을 때만 bidi를 검토한다.

`.proto`의 이점은 RPC method와 요청/응답 message를 명시적이고 version 관리 가능한 schema로 정의하고, Java·Python client/server stub과 자료형 코드를 생성할 수 있다는 점이다. protobuf는 binary wire format이라 일부 payload에서 작고 빠를 수 있으나, DTO가 작고 DB 지연이 큰 현재 조회에서 이득은 측정 대상이다. 필드 번호 변경 규칙, code generation/build 단계, 사람이 바로 읽기 어려운 payload, 브라우저·프록시·운영 도구 호환 부담이 생긴다. proto를 썼다는 사실 자체가 성능 향상을 증명하지는 않는다. [gRPC service definitions](https://grpc.io/docs/what-is-grpc/core-concepts/).

**선택 기준:** REST/HTTP2 pooling으로 성능이 충분하면 REST 유지. MCP는 도구 발견·공통 agent tool catalog가 필요한 경계에만 사용. gRPC unary는 측정에서 serialize/transport 지연이 유의미한 비중을 차지하고 실제 p95/throughput/CPU 개선이 codegen·운영 비용을 넘을 때 채택. gRPC streaming은 지속되는 한 요청의 message exchange가 제품 요구에 있을 때만 선택한다. 현재 OnMaru online query에는 gRPC bidi를 넣지 않는 것이 자연스럽다.

### OnMaru에서 gRPC를 넣을 때의 서비스 경계

gRPC 도입은 Spring/FastAPI 책임을 합치거나 DB를 공유하는 구조 변경이 아니다. 두 애플리케이션은 계속 별도 프로세스이며, 내부 네트워크 호출에서만 특정 adapter를 교체한다. public API·회원 인증·conversation state·최종 검증·SSE는 Spring 소유로 유지하고, Query Processing·retrieval·generation은 FastAPI 소유다. 한 번에 한 서비스 경계만 바꾸고 OTel trace와 동일한 grant/auth policy를 유지한다.

| OnMaru 호출 형태 | gRPC 유형 | 쓸 만한 시점 | 현 설계 판정 |
|---|---|---|---|
| Spring이 FastAPI에 plan/execute 한 건을 보내고 결과를 받음 | Unary RPC | REST 재사용 상태를 측정했는데 해당 내부 hop이 사용자 p95에서 의미 있는 비중을 차지하고, proto 계약의 이익도 있을 때 | 첫 gRPC 실험 후보. 사용자 REST 요청을 gRPC로 바꾸는 것이 아니라 내부 Spring→FastAPI hop만 비교 |
| FastAPI가 Spring에 승인된 정형 장소 조회 한 건을 요청 | Unary RPC | `PlaceQueryPort`의 REST 호출 지연이 분명한 병목일 때 | 같은 Spring port에 붙일 별도 비교 후보. DB 실행 시간을 따로 분리 측정 |
| Spring이 FastAPI의 한 run을 시작하고 FastAPI가 진행/결과 event 여러 개를 보냄 | Server streaming RPC | 내부 응답을 연속 전송하는 것이 현재 Spring SSE 브리지보다 실질 이점이 있을 때 | 검증된 문장만 전송하는 현재 정책에선 필요성이 낮음. 도입하면 Spring이 gRPC stream을 SSE로 변환 |
| Spring이 활성 처리 중 새 조건·제어 메시지를 보내고 FastAPI도 같은 호출에서 이벤트를 계속 보냄 | Bidirectional streaming RPC | 사용자가 한 run을 취소·수정·추가 지시하고 AI가 실행 중에 이에 반응해야 하는 제품 흐름이 생길 때 | 지금은 불필요. 별도 세션 수명·sequence/idempotency·reconnect/resume·backpressure·stream affinity 설계가 선행돼야 함 |

gRPC bidi의 적절한 구조는 예를 들어 Spring이 client로 `InteractiveRun(stream ClientCommand) returns (stream EngineEvent)`를 열고, FastAPI가 `Accepted`, `StageChanged`, `ValidatedCandidate`, `Completed`, `Failed` 같은 typed event를 보내는 형태다. Spring은 브라우저와의 연결을 계속 REST/SSE로 관리하고, 사용자가 보내는 다음 turn/수정은 인증·소유권을 다시 검사한 뒤 활성 gRPC stream으로 전달한다. **사용자 대화 thread 전체를 무기한 gRPC 연결로 유지하지 않는다.** 각 진행 run에 수명 제한을 둬야 하며, 재연결 때는 저장된 event sequence와 run snapshot으로 복구한다.

이 구조가 필요해지기 전에는 요청마다 독립적인 Spring→FastAPI unary RPC/REST가 자연스럽다. 사용자 취소도 Spring REST command가 run을 취소하고, Spring이 진행 중 unary/RPC를 취소하는 방식으로 처리할 수 있어 bidi를 요구하지 않는다. gRPC 공식 지침은 stream이 시작되면 기존 stream을 다른 backend로 옮길 수 없고 장애 복구·부하분산이 복잡해질 수 있으므로, 지속적인 양방향 application flow가 있을 때만 쓰라고 권고한다. [gRPC performance guidance](https://grpc.io/docs/guides/performance/), [gRPC cancellation](https://grpc.io/docs/guides/cancellation/).

안전성 또한 MCP 채택만으로 높아지지 않는다. 좁은 port, Spring 인증/인가, run-bound grant, 입력/출력 검증, parameterized repository query, 최소 권한 DB role, private network/TLS가 통제 근거다. `readOnlyHint`만 보고 호출을 신뢰하지 않는다. Spring AI MCP starter 자체가 인증·인가를 자동 제공하지 않으므로 Spring Security 및 tool 단위 scope 검사를 직접 설정해야 한다. [Spring AI MCP server starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html).

### `journey_execute_approved_lookup` 계약

- 입력은 `queryGrantId`만 받는다. `safeQuery`, 자유 검색식, 필터, place ID 배열, SQL, table/field 이름, URL, member ID를 입력으로 받지 않는다.
- `queryGrantId`는 Spring이 `PlanProposal`을 검증하고 `ApprovedQueryPlan`을 저장한 뒤 발행하는 추측 불가능한 opaque reference다. 30초 후 만료되며 한 `runId`·한 plan hash·한 `PlaceQueryPort` operation·한 허용 field set·한 상한(최대 5개)에 결합한다.
- grant 발행 전 Spring은 회원 소유권, 공개 상태, 지역/제외/고정 조건, 허용 canonical field, source revocation sequence, corpus/config/plan version을 확인한다. grant에는 실제 회원 식별자와 권한 정보가 외부 claim으로 노출되지 않는다.
- Grant는 Spring DB에 token hash와 `runId`, plan hash, field/filter hash, 결과 한도, expiry, 상태(`ISSUED|EXECUTED|REVOKED|EXPIRED`)를 저장한다. grant 원문은 DB에 저장하지 않는다. 첫 실행 결과의 제한된 public projection과 response hash를 짧게 보존해 같은 grant 재전송에는 동일 결과를 반환하고, 다른 plan/operation으로 재사용하면 `GRANT_MISMATCH`로 거부한다. receipt는 최대 60초 내 삭제하고 grant는 30초 후 새 query를 시작할 수 없게 한다. 이 저장 구조는 구현 PR에서 Spring DB schema와 GC 계약으로 확정한다.
- 서버는 등록된 parameterized query template만 실행하고 결과를 `PlaceLookupResult.v1`로 반환한다. 출력 필드는 승인된 요청 필드의 부분집합이며, source 상태 재확인 후 현재 공개 장소만 포함한다. 회원 전용 이력·찜·원문 대화는 조회하지 않는다.
- 이미지 주소는 모델이 합성한 URL이 아니라 서버가 검증한 `mediaAssetId` 또는 승인된 public media reference만 반환한다. 모델이 출력에 임의 주소를 추가할 수 없다.
- 출력은 최대 5 places, 최대 24 KiB. 내부 DB 행·SQL·예외 stack·회원 정보는 결과나 오류에 포함하지 않는다.
- 응답에는 `sourceRevocationSequence`를 포함한다. Spring은 query 실행 시점의 sequence를 기록하며, FastAPI·최종 Spring validator가 실행/확정 시 현재 값과 다르면 해당 run을 stale로 실패 처리한다.

허용 canonical field v1은 `PUBLIC_PLACE_ID`, `DISPLAY_NAME`, `REGION_CODE`, `CATEGORY_CODE`, `PUBLIC_ADDRESS`, `OPENING_HOURS_SUMMARY`, `PUBLIC_MEDIA_REFS`, `PUBLICATION_STATUS`다. 위도/경도·개인 위치·방문 이력·후기 원문·미검증 작품 관계는 v1 projection에서 제외한다. 사용 사례가 필드를 더 요구하면 schema/allowlist/승인 테스트를 함께 변경한다.

## 온라인 요청 흐름과 권한 발급

1. Spring은 회원 인증·한도·개인정보 제거·대화 맥락 구성 후 기존 private REST plan endpoint로 FastAPI를 호출한다. 이 요청은 오케스트레이션 계약이고, MCP가 대체하지 않는다.
2. FastAPI `Query Processing Layer`는 `QueryFrame`을 만들고 결정적 `Search Router`가 `SQL_ONLY`, `SQL_PLUS_RETRIEVAL`, 검색 전용 route 또는 clarification을 제안한다. 모델이 MCP tool을 고르지 않는다.
3. Spring `PlanAuthorityGate`는 schema, 필드·필터 부분집합, 공개·철회 상태, 요청 scope, version을 검증한다. 유효한 정형 조회가 있으면 Spring이 제한된 `queryGrantId`를 발급한다. 미승인/모호/범위 밖 계획은 grant를 만들지 않는다.
4. 기존 private execute orchestration call에 승인 plan hash와 opaque grant reference만 포함한다. AI가 이 호출을 수신하면 `ApprovedPlanExecutor`가 route→operation mapping을 검증하고 기본 REST adapter로 `PlaceQueryPort`를 호출한다. MCP adapter가 선택된 작업에서만 고정 tool `journey_execute_approved_lookup`을 사용한다. 어느 경우도 LLM의 function-call 판단이 아니라 결정적 Python 코드의 typed invocation이다.
5. `SQL_PLUS_RETRIEVAL`에서는 Spring typed lookup과 FastAPI 내부 retrieval을 독립 span으로 실행한다. 결과를 근거/장소 ID로 합치되 한쪽 오류가 route를 바꾸지 않는다. `SQL_ONLY`는 typed result를 deterministic renderer에 넣으며 Gemini·embedding·검색을 호출하지 않는다.
6. FastAPI는 typed result와 승인 plan 참조를 기존 execute 결과로 Spring에 반환한다. Spring은 공개·철회·회원 범위를 다시 검증하고 DB snapshot 저장과 SSE 확정을 한다.

각 run에서 온라인 정형 lookup은 필요에 따라 0회 또는 1회다. route 실패 후 다른 tool이나 검색기로 자동 재계획하지 않는다. Spring lookup 오류 시 같은 run에서 이미 승인된 기본 탐색으로 제한하거나 명시적 실패한다. `SQL_PLUS_RETRIEVAL` 오류 시 정형 claim은 제거하고, RAG 근거가 독립적으로 충분한 claim만 남긴다.

## 인증·격리·관측

- Online FastAPI principal에는 transport와 무관하게 `journey.query.execute`만 부여한다. Quality job은 별도 principal로 `quality.read.run` 또는 `quality.read.source`만 받는다. online token으로 quality 자료를 조회할 수 없고 quality token으로 온라인 장소 lookup을 호출할 수 없다.
- 서로 다른 audience, 짧은 수명, TLS, key rotation/revocation, Spring Security 및 private network를 적용한다. 기존 Spring→FastAPI bearer를 역방향 호출에 재사용하지 않는다. MCP starter는 인증 정책을 대신하지 않으므로 MCP endpoint 및 tool authorization을 명시 설정한다.
- logical MCP surface를 `/internal/mcp/journey`와 `/internal/mcp/quality`로 분리한다. Spring AI 설정이 동일 프로세스에서 독립 scope/endpoint를 보장하지 못하면 MCP server adapter/context를 분리해 구성한다. public Nginx upstream에는 어느 endpoint도 등록하지 않는다.
- MCP handler는 controller처럼 얇게 두고 application input port를 호출한다. tool annotation method 안에서 SQL 문자열을 조립하지 않는다. Repository는 고정 parameterized projection query만 실행한다. DB role은 가능한 경우 해당 read projection의 SELECT 권한만 갖게 하며, 기존 Spring datasource가 더 넓은 권한이면 이를 MCP 경로의 강한 DB read-only 보장으로 간주하지 않는다.
- 출력 상한 24 KiB, 정형 lookup 1회/run, concurrent calls는 staging 시작 시 1개/principal을 제안한다. connect/전체 timeout은 운영 trace와 하위 DB/provider 분포를 확인해 구현 시 정하며 상위 run deadline을 넘지 않는다. 401/403/404/422는 재시도하지 않는다. network reset/502/503만 같은 opaque grant를 재호출해 최대 1회 재시도한다. Spring은 저장된 실행 receipt를 반환해 결과 변화를 막는다.
- 출력·동시성 제한은 초기 계약 제안이며 staging에서 검증한다. Spring↔FastAPI와 PlaceQueryPort의 latency 목표는 실측 기준선을 얻기 전 수치로 고정하지 않는다.
- 닫힌 오류 code는 `SCOPE_DENIED`, `INVALID_GRANT`, `GRANT_EXPIRED`, `GRANT_REVOKED`, `GRANT_MISMATCH`, `REVOCATION_STATE_STALE`, `OUTPUT_LIMIT_EXCEEDED`, `RATE_LIMITED`, `UPSTREAM_UNAVAILABLE`다. 인증/인가·grant·철회·크기 오류는 재시도하지 않고, transient network/502/503만 같은 grant로 최대 1회 재시도한다.
- OTel child span `spring.place_query.lookup`에는 `transport=rest|mcp|grpc`, operation version, outcome, latency, output bytes, plan route, error code만 기록한다. queryGrantId, 입력/출력 본문, member ID, 주소는 span/log/metric label에 기록하지 않는다. `runId` correlation은 접근 제어된 artifact에서만 연결한다.
- 선택된 adapter의 장애는 인증·final validation·SSE 저장 경계를 우회하거나 막지 않는다. 최종 Spring 검증은 adapter 응답을 신뢰하지 않고 기존 source-of-truth service로 다시 확인한다.

## 구현/평가 게이트

1. REST를 운영 기준선으로 두고 typed port contract test와 권한 테스트를 통과시킨다. MCP를 쓸 때는 Spring Boot/Spring AI MCP starter 조합과 generated schema/catalog 일치를 검증한다.
2. 정상·expired/revoked/다른 run grant·다른 plan hash·scope 부족·5건 초과·비공개 장소·철회 source·잘못된 인자를 테스트한다. 정책 위반은 fail closed여야 한다.
3. REST/MCP/gRPC adapter가 같은 typed input/output, timeout, bounded retry, payload no-log 및 오류 mapping을 만족하는지 검증한다. MCP는 tool name/catalog hash, gRPC는 proto compatibility와 W3C trace metadata 전파를 검증한다.
4. gold 평가에서 SQL route 정확도, 불필요 lookup 비율, 조회 결과/계획 일치율, 권한 초과 조회 0건, SQL_ONLY의 LLM/embedding 호출 0회, SQL_PLUS_RETRIEVAL 합성 근거 지지율을 확인한다.
5. 동일 run에서 Spring root trace → plan REST → PlanAuthorityGate → 선택된 REST/MCP/gRPC child span → AI retrieval/generation(필요 route만) → Spring final validation/SSE 연결을 staging에서 확인한다.
6. 먼저 Spring↔FastAPI plan/execute REST의 hop 및 end-to-end trace 분포를 수집한다. 해당 hop이 병목일 때만 동일 payload·업무 로직·동시성으로 REST와 gRPC unary를 비교하고 warm/cold p50/p95/p99, 처리량, 오류, payload, CPU/RSS, serialization 및 DB 시간을 수집한다. gRPC는 측정 이득이 개발·배포·관측 복잡도를 상쇄할 때만 채택한다. `PlaceQueryPort` 비교는 별도 실험이며 2GB Lightsail 혼합 부하도 확인한다.
