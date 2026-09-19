# 이야기길: 여정 탐색 확장 기획 기록

> **ARCHIVED PLANNING RECORD. 구현 기준으로 사용하지 않는다.** 이 폴더는 2026-09-09~10의 제품 탐색과 7일 제출 가설을 보존한다. 현재 여정 계약은 [Public REST API](../../contracts/rest-api.md), [Journey OpenAPI](../../contracts/openapi/journey.openapi.yaml), [SSE event schema](../../contracts/schemas/journey-sse-event.schema.json), [Frontend handoff](../../contracts/frontend-handoff.md)를 따른다. 현재 AI/RAG/운영/DB 기준은 각각 `docs/ai`, `docs/operations`, `docs/database`다.


2026-09-09 | 검토용 제안 v0.1 | 제품·기술 설계이며 구현 완료 문서가 아니다.

**추천 방향: 듣던 이야기에서 방문 후보를 발견하고, 마음에 든 장소는 고정한 채 주변 선택지를 근거와 함께 바꾸는 탐색 보드.** 탭 이름은 `이야기길`을 제안한다. `/discover` 경로는 유지한다. 이름과 최초 지원 지역은 승인 대상이다.

기존 [백엔드 PRD](../backend-prd.md)는 도슨트 Q&A 중심의 최소 범위였다. 이번 추가 요청으로 주간 추천, 게시형 큐레이션, 서버 제공 온기, 다중 장소 탐색을 명시적으로 확장한다. 기존 기능을 모두 구현했다는 뜻도, 아래 초안이 승인됐다는 뜻도 아니다.

## 읽는 순서

1. [FE 데이터 감사](fe-data-audit.md): 실제 소스와 명칭의 차이, 서버 이관 범위.
2. [브레인스토밍·Red/Blue 평가](brainstorming-review.md): 18개 아이디어, 상위 3개, 3라운드 반론.
3. [제품 PRD](product-prd.md): 차별화, 사용자 흐름, 범위, 인수 기준.
4. [백엔드·추천·AI 설계](architecture-and-recommendation.md): 소유권, 모델 비교, 관계 검색, LangGraph, harness.
5. [FE·API 전달서](fe-api-handoff.md): 컴포넌트 동작, HTTP/SSE, 버전·재접속·실패 계약.
6. [공모전 평가](competition-review.md): 2026 공식 배점, 검증 계획, 시연 구성.
7. [이슈·ADR 영향](implementation-impact.md): 기존 W0~W11와 신규 작업 후보의 연결.
8. [핵심 경험 검토](kick-brief.md): 차별화 주장 검토와 질문·현장 관찰 가설.
9. [웹·앱 여정 탐색과 회원 경험](journey-service-plan.md): 후속 사용자 요청인 로그인·회원 기능, 폭넓은 데이터 탐색, 오디와의 역할 분담 및 JE 요구. 웹·앱 연속 사용 방향을 반영했으며 세부 정책은 검토 중이다. 페이지명은 `여정 탐색`을 작업 기준으로 하고 `이야기길`은 경험 이름 후보로 남긴다.
10. [7일 MVP FE 전달서](seven-day-mvp-fe-handoff.md): 당시 제출 우선순위 가설. transport·DTO·범위는 현재 계약이 아니며, 2026-09-12부터 AI 여정은 REST command + SSE notification + GET snapshot으로 변경됐다.
11. [AI 여정 탐색 FE 경험·API 구현 보고서](fe-experience-api-implementation-report.md): 세로 대화와 가로 여정의 결합, 상대 거리 rail, 변경안 비교, 모바일 반응형 구조, API-to-component mapping, 상태 소유권과 FE 완료 기준을 구체화한다.
12. [환경·릴리스·성공 게이트](staging-demo-release-and-success-gates.md): staging을 실사용 검증 기준으로 삼고 demo를 동일 계약의 비상 시연 환경으로 격리하는 방식, 환경 설정 불변식, 제품·시연 지표와 라이브 심사 runbook을 정한다.

## 근거의 수준과 미확인 입력

- FE 소스 기준: `/Users/yangseunghyeon/Development/OnMaru/OnMaruFE`, commit `3a08c2a5069a762d25ad1f5e0c78e6f659ebbbfa`. 소스 정적 분석이며 화면·실 API 동작 검증은 하지 않았다.
- `docs/specs`보다 실제 소스에 먼저 구현된 `/discover` 프로토타입이 있다. 계약이 다르면 [감사표](fe-data-audit.md)의 실제 요청/응답과 제안 계약을 구분한다.
- 사용자 메시지의 “아래 두 개 내용”은 본문·첨부·경로가 전달되지 않았다. 해당 두 자료를 읽었다고 간주하지 않는다. 전달 후 경쟁 대안·흐름·계약을 재검토한다.
- 공모전은 로컬 공식 PDF의 **2026 웹·앱 개발 부문**을 잠정 기준으로 사용했다. 실제 참가 부문 확인이 필요하며 구현 부문에 이 배점을 적용하지 않는다.
- 실제 관광 데이터 커버리지, 저작권·AI 가공 허용 범위, 지도 경로 공급자, 팀 인원·예산·제출 마감은 미확정이다. 운영비·속도·정확도 수치는 측정 결과가 아니라 실험의 승인 기준이다.
- 공공데이터를 연결했다고 곧바로 관광객 분산·방문 증가를 달성했다고 주장하지 않는다. 조회·선택·길찾기 클릭과 실제 방문은 다른 지표다.

## 이번 결정의 상태

| 항목 | 제안 | 현재 상태 |
|---|---|---|
| 제품 초점 | 이야기 기반 발견 + 선택 보존형 부분 재탐색 | 사용자 검토 필요 |
| 그래프 | 출처 있는 관계를 작은 맥락 뷰로 표현 | 대형 그래프 DB 도입 아님 |
| 화면 생성 | 허용된 카드·지도·플레이어 계약의 조합 | 임의 코드 생성 금지 |
| 추천 | SQL·편집 기준선, 임베딩/재순위화 비교 실험 | 모델 선정 전 |
| 전송 | 당시 제출 가설은 REST polling + HTTP snapshot | **폐기됨.** 현재 AI 여정은 SSE notification + HTTP snapshot, 나머지 기능은 REST |
| 구현 착수 | 계약/데이터 근거 검증 후 이슈 발행 | 외부 이슈·정식 신규 ADR 미등록 |

이 문서는 로컬 심볼릭 링크의 FE 원본을 수정하지 않는다. 구현 전 W0에서 증거 파일 목록과 해시를 저장소 내부에 고정해야 다른 개발자와 CI가 같은 기준을 재현할 수 있다.
