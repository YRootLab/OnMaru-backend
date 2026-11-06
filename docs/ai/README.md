# 공통 AI 서비스 계약과 기존 구현

`docs/ai/`는 FastAPI 경계, 내부 인증, provider adapter, 출력 검증, 기존 여정 AI 구현의 공통 계약을 보관한다. 새 홈 여정의 RAG 제품·엔진 설계는 [onmaru-ai-engine의 AI-RAG 정본](https://github.com/YRootLab/onmaru-ai-engine/tree/docs/ai-rag-transfer/docs/ai-rag)으로 이전했다. 이 저장소에는 Spring 연동·공유 계약·DB/API·배포 구현 계획을 남긴다. 목표 설계는 ADR-0017과 AI-RAG 정본을, 배포된 wire 동작은 코드·migration·OpenAPI를 확인한다.

새 홈 여정의 기본 검색은 한국어 BM25 + vector → RRF → reranker다. 온라인 응답에 자유형 agent loop를 두지 않는다. 검색·생성 및 오프라인 품질 감독의 세부 설계는 AI-RAG 정본이 소유하고, Spring이 제공하는 정형 조회·MCP tool·공개 API와 데이터 계약은 이 저장소가 소유한다.

별도 Python 저장소 [onmaru-ai-engine](https://github.com/YRootLab/onmaru-ai-engine)은 2026-10-22에 생성했다. AI-RAG 문서·보고서의 첫 이전 commit은 `00847c174043ccf6d542a6ac15c595556ce8da3f`이며, `onmaru_ai.quality_harness`는 품질 평가·실패 분류·운영자 선택형 조사와 실험을 맡는 목표 모듈 이름이다. FastAPI runtime 코드와 운영 배포는 아직 이전되지 않았다. 저장소·배포 소유권은 [AI 저장소 분리 계획](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/repository-split-and-monitoring.md)과 이 저장소의 [Spring/Lightsail 통합 계획](../ai-rag-spring/repository-and-deployment.md)을 따른다.

| 문서 | 역할·상태 |
|---|---|
| [Application architecture](application-architecture.md) | Spring/FastAPI/provider 책임의 공통 원칙. 2026-09의 optional RAG·단발 proposal 부분은 과거 설계. |
| [Internal service authentication](internal-service-authentication.md) | 서비스 간 인증·TLS·실패 경계의 공통 설계. |
| [Gemini provider adapter](gemini-provider-adapter.md) | provider 어댑터 운영 계약. |
| [Proposal validation](proposal-validation.md) | 현재 기존 proposal 구현의 closed schema·최대 3개 검증. 새 3~5개 계약으로 전환 시 수정 필요. |
| [Evaluation harness](evaluation-harness.md) | 기존 offline 평가 하네스. 새 RAG 품질 루프는 [운영·평가](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/operations-and-evaluation.md) 참조. |
| [Corpus sync contract](corpus-sync-contract.md) | manifest·tombstone·ACK의 공통 프로토콜. 새 코퍼스 범위는 [RAG 아키텍처](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/architecture.md) 참조. |
| [Journey guardrails](journey-guardrails.md) | 2026-09 단발성 여정 proposal의 기존 계약 기록. 일일 한도·최대 3개·optional RAG를 새 제품 정책으로 사용하지 않음. |

`application-architecture.md`와 `journey-guardrails.md`에는 과거 optional RAG·일일 한도 표현이 있지만 현재 REST 계약·구현 이력의 참조가 남아 있어 **현행 구현/전환 참고자료**로 유지한다. `proposal-validation.md`도 현재 proposal schema를 설명하므로 새 계약이 배포되기 전에는 삭제하지 않는다. 중복된 optional 검색 초안은 제거했고, 품질 harness의 목표 설계는 AI 저장소의 [상세 설계](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/quality-engineering-agent.md)를 따른다. Spring 구현 지침은 `docs/ai-rag-spring/`, shared contract와 ADR-0017을 따른다.
