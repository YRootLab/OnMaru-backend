# Spring 측 AI-RAG 통합 설계

> 상태: 설계 제안. 이 폴더는 OnMaru-backend가 소유하는 AI-RAG 경계, 내부 조회 도구, 인증과 운영 계약을 정리한다. 사용자 답변/RAG 전체 목표는 [docs/ai-rag/README.md](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/README.md), 공유 wire 계약은 [docs/contracts/](../contracts/)가 정본이다.

## 문서

- [백엔드 통합 PRD](backend-integration-prd.md): Spring/API·DB·보안·관측·배포 책임, 완료 기준, AI 저장소와의 구현 경계 및 순서.
- [여정 정형 조회 경계와 서비스 간 전송](journey-query-mcp.md): `PlaceQueryPort`와 SQL 권한 소유권, Spring↔FastAPI REST 기준선 및 관측 후 gRPC unary 비교, MCP tool discovery, grant·scope 정책.
- [Quality Harness용 내부 MCP 도구 계약](quality-tools-mcp.md): Spring이 소유한 실행 관측과 공개 원천 상태를 AI 품질 하네스에 읽기 전용으로 제공하는 범위, 도구 schema, 인증, 실행·오류·감사 정책.
- [AI 저장소와 Lightsail 배포 통합](repository-and-deployment.md): AI 코드와 Spring 인프라 저장소의 책임, 한 인스턴스 배포·릴리스 pairing·공존 검증 게이트.
- [ADR-0018 제안](../decisions/0018-quality-harness-spring-mcp-tools.md): MCP와 REST/직접 DB 대안, MCP 서버 도입 여부를 검토하기 위한 구조 결정 초안.
- [ADR-0019 제안](../decisions/0019-journey-query-mcp-tools.md): `PlaceQueryPort` 경계, MCP 사용 조건, Spring↔FastAPI REST 기준선과 병목 확인 후 gRPC unary 실험을 정리한 구조 결정 초안.
- [Journey query MCP catalog](../contracts/mcp/journey-query-tools.v1.json): 온라인 tool schema·grant·호출·응답 제한의 공유 fixture.
- [MCP tool catalog v1](../contracts/mcp/quality-tools.v1.json): 도구의 입력·출력 JSON Schema와 advisory annotation을 담는 공유 계약 fixture.

## 소유권 원칙

Spring은 회원·대화·업무 DB·정형 SQL·공개/철회 권한과 온라인 run 관측의 정본을 소유한다. AI Engine은 corpus·검색·평가·조사/실험 artifact를 소유한다. AI quality harness는 Spring 업무 DB에 직접 접속하지 않는다. 반대로 Spring이 AI 평가 DB의 임의 SQL을 실행하지 않는다.

MCP는 별도 에이전트 런타임이나 권한 체계가 아니다. 제한된 Spring 내부 서비스를 AI 측 typed tool client에서 호출하기 위한 표준 프로토콜 경계다. Online query tool과 quality 도구는 endpoint/scope/principal을 분리한다. Spring 서비스 로직은 MCP 전용으로 복제하지 않고 기존 application service를 호출한다. Query Router는 deterministic code이며 LLM이 임의로 MCP tool을 고르는 loop가 아니다.

## 구현 전제

ADR-0018이 승인되고 Spring Boot/Spring AI 버전 호환성과 Spring Security 적용을 검증하기 전에는 MCP dependency·endpoint를 추가하지 않는다. 구현 시 MCP HTTP endpoint는 private network/TLS와 service authentication으로만 접근 가능해야 하며, public Nginx 경로에 노출하지 않는다.
