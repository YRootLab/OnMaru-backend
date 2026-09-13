# FastAPI AI 설계

FastAPI는 Spring이 준비한 후보와 증거만 받아 입력 정책, prompt package, LLM provider adapter, typed proposal 검증을 담당한다. 공개 REST, 관광 데이터 수집, PostgreSQL 직접 접근은 Spring 책임이다.

- [AI application architecture](application-architecture.md): trust boundary, provider-neutral adapter, prompt/eval harness, 단계적 RAG
- [Journey guardrails](journey-guardrails.md): 입력 분류, 금칙/개인정보 처리, quota, typed output, 실패 경로
- [Retrieval and optional RAG](retrieval-and-rag.md): deterministic retrieval 우선과 optional RAG 진입 조건
- [Corpus sync contract](corpus-sync-contract.md): Spring manifest, tombstone, ACK, FastAPI activation pointer
- [Internal service authentication](internal-service-authentication.md): jti replay TTL, key rotation, outage and Grafana runbook proof

구현 전 게이트: Gemini의 실제 free-tier 한도/약관 확인, synthetic eval fixture, schema validation, timeout/실패 시 baseline 전환을 검증한다.
