---
id: ADR-0018
title: 품질 하네스가 Spring 소유 사실을 읽는 내부 MCP tool 경계를 제안한다
status: proposed
date: 2026-10-22
locale: ko
decision_makers:
  - 사용자 검토 필요
related:
  - ADR-0017
affected_paths:
  - docs/ai-rag-spring/
  - docs/ai-rag/
  - docs/contracts/
tags:
  - ai
  - quality-harness
  - mcp
retrospective: false
---

# 품질 하네스가 Spring 소유 사실을 읽는 내부 MCP tool 경계를 제안한다

## 상태와 맥락

상태는 제안이다. 설계 방향은 AI 품질 하네스가 Spring 소유 실행 기록·게시/철회 상태를 좁은 read-only 도구로 조회하도록 하는 것이지만, Spring MCP server 구현·의존성 도입·운영 배포가 승인되거나 완료된 것은 아니다.

OnMaru Backend(Spring)와 목표 AI Engine(FastAPI)은 별도 저장소·서버 프로세스로 개발한다. quality_harness는 Python workflow와 typed tools로 AI 자체의 평가 case, 검색 trace, corpus index를 검사한다. Spring 업무 DB에 직접 연결하면 AI에 회원/업무 스키마 권한을 추가로 주게 되며, 같은 tool을 별도 REST/MCP layer 없이 여러 AI orchestration에서 쓰기 어렵다. 반면 MCP를 무조건 도입하면 별도 endpoint 보안과 protocol lifecycle 비용이 생긴다.

## 제안 결정

- Spring은 Spring이 소유하는 비식별 RunObservation과 source publication/revocation state만 제공하는 **private, read-only MCP server**를 둔다.
- AI-side quality_harness는 이 MCP server의 client이고, 평가·corpus·ranking artifact 조회는 기존처럼 Python typed function으로 처리한다.
- MCP endpoint는 public Nginx/API로 노출하지 않고 private network/TLS와 별도 service identity/scope로 보호한다. tool 등록만으로 인가가 되지 않으므로 Spring Security와 application-level authorization을 적용한다.
- MCP tool은 일반 DB/파일/URL/shell 또는 write tool을 제공하지 않는다. AgentFinding/ExperimentSpec은 AI-side typed writer만 저장하며 설정·corpus promotion과 human review는 별도 관리자/배포 경계에 둔다.
- 도구 catalog는 [quality.tools.v1](../contracts/mcp/quality-tools.v1.json), 상세 권한·제한·오류 계약은 [Spring MCP tool 설계](../ai-rag-spring/quality-tools-mcp.md)에서 관리한다.

## 도입 게이트

이 제안이 승인되어도 구현 PR은 다음 조건을 통과해야 한다.

1. Spring Boot 4.1.1과 구현 당시 Spring AI MCP starter의 버전 호환성 및 Streamable HTTP 지원을 고정한다.
2. HTTP endpoint 인증/인가가 starter에서 자동 제공된다고 가정하지 않는다. private 네트워크, TLS, 인증, tool별 scope, key rotation, fail-closed 동작을 입증한다.
3. run/source 조회 응답에 사용자 원문·회원 ID·대화·문서 본문이 없고 응답 크기·timeout·retry가 제한되는 계약 테스트를 둔다.
4. MCP/AI 품질 작업 장애가 온라인 사용자 API/SSE를 늦추거나 실패시키지 않음을 staging에서 검증한다.
5. 2GB Lightsail 혼합 부하·메모리·관측 비용을 확인한다.

## 대안

| 대안 | 평가 |
|---|---|
| AI가 Spring 업무 DB를 직접 조회 | 거부. 데이터 소유권·최소 권한·스키마 변경 독립성을 깨고 회원 정보가 품질 도구에 과도하게 노출될 수 있다. |
| 모든 도구를 FastAPI Python function으로 구현 | AI 소유 데이터에는 적절하다. Spring 소유 원천/온라인 상태의 복제본을 만들지 않고 최신 권한을 확인해야 할 때 원격 경계가 필요하다. |
| 일반 내부 REST endpoint | v1 fallback으로 유효하다. 동일한 typed API만 필요하면 MCP보다 간단하며, MCP client/server 호환성·인증 비용이 효과보다 크면 이 경로를 선택한다. |
| 모든 접근을 MCP로 통일 | 거부. AI repository 안의 순수 로컬 검색·평가 function에 원격 protocol을 강제할 이유가 없고 관측·오류 지점을 늘린다. |

## 결과와 재검토

승인 시 Spring의 좁은 데이터 조회 권한을 서비스 인터페이스로 분리하고 AI 하네스의 tool orchestration을 저장소 간 독립 계약으로 검증할 수 있다. 비용은 MCP client/server dependency, 인증 방향 추가, tool schema 및 통합 테스트 운영이다.

MCP 도입은 Spring Boot/Spring AI compatibility, private service auth, 운영 자원, 고정 tool catalog의 통과 여부를 기준으로 재검토한다. 하나라도 합격하지 못하거나 향후 MCP 호환 클라이언트 수요가 없다면 내부 REST endpoint와 Python typed adapter를 사용한다. 양쪽 경로 모두 Spring 업무 DB 직접 접근은 허용하지 않는다.
