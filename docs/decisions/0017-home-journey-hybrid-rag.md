---
id: ADR-0017
title: 홈 여정 대화에 PostgreSQL 하이브리드 RAG를 기본 경로로 사용한다
status: accepted
date: 2026-10-19
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
  - ADR-0007
affected_paths:
  - docs/ai-rag/
  - docs/ai/
  - docs/contracts/
  - docs/database/
  - infra/lightsail/
tags:
  - ai
  - rag
  - journey
retrospective: false
supersedes:
  - ADR-0007
---

# 홈 여정 대화에 PostgreSQL 하이브리드 RAG를 기본 경로로 사용한다

## 맥락

홈 여정 검색을 근거가 풍부한 다턴 대화로 확장하고, 장소의 역사·문화·방문 포인트를 출처와 함께 설명하려면 기존 후보 정렬 전용 proposal보다 공개 본문 검색이 필요하다. 기존 [ADR-0007](0007-baseline-optional-rag.md)은 deterministic baseline을 기본으로 두고 RAG를 평가 통과 후 선택 활성화하기로 했지만, 이번 제품 목표와 사용자의 새 결정은 **첫 AI RAG 릴리스부터 하이브리드 검색을 기본 경로로 사용**하는 것이다. ADR-0007은 당시 판단의 기록으로 보존한다.

## 결정

- Spring은 회원·run·한도·원문 대화·입력 비식별화·공개 자격·정형 SQL·최종 응답을 소유한다. FastAPI AI 엔진은 비식별 질문의 해석·`PlanProposal`/route 선택, 공개 revision의 corpus 동기화·임베딩·lexical/dense 검색·근거 제한·LLM 제안·형식 검증을 소유한다. Spring은 제안의 필드·ID·필터·version을 승인한 뒤 SQL을 직접 실행하고 필요한 본문 경로만 AI 엔진에 다시 맡긴다. `SQL_ONLY`도 AI plan 호출은 거치지만 검색·LLM 호출은 없다.
- 첫 RAG 릴리스에 한국어 BM25와 pgvector exact cosine을 같은 공개·지역 hard filter 아래 검색하고 RRF로 결합한다. 상위 후보는 reranker로 재정렬해 최종 근거를 고른다. `pg_search`는 PostgreSQL 안의 BM25 구현 1차 후보이며 한국어 tokenizer·PostGIS/pgvector 공존·라이선스·목표 2GB 용량 게이트를 통과해야 한다. 미통과 시 검색 구현과 서버 용량을 재결정한다.
- 첫 RAG 릴리스부터 AI가 제안하고 Spring이 실행하는 정형 `SQL_ONLY`, FastAPI의 `LEXICAL_ONLY`·`DENSE_ONLY`·`HYBRID`, Spring SQL과 검색을 결합하는 `SQL_PLUS_RETRIEVAL`을 모두 활성화한다. 이름·별칭/정확 용어 단독은 lexical, 고유명사·부정이 없는 단일 의미 주제는 dense, 복합 설명·추천·비교와 불확실한 본문 유형은 hybrid를 결정적으로 선택한다. route별 gold 평가·오라우팅·2GB 부하 시험은 전체 출시 게이트다. 평가 실패 경로를 조용히 비활성화한 채 출시하지 않고 정책과 구현을 재검토한다.
- 벡터 DB·FastAPI 전용 서버는 만들지 않는다. 기존 1GB/$7 Lightsail을 스냅샷으로 **2GB/$12 단일 인스턴스**로 이전해 Spring·PostgreSQL/PostGIS/pgvector/BM25·Nginx·FastAPI·질문 임베딩·reranker를 함께 둔다. 문서 임베딩 배치는 같은 호스트에서 온라인 피크와 분리해 실행한다. 혼합 부하·복구·기존 API의 **모델 포함 2GB 공존 게이트를 출시 조건**으로 둔다. 미통과 시 모델·색인·배치·연결·검색 구현을 조정하고 출시를 보류하며, 추가 서버나 상위 플랜은 별도 결정이 필요하다. FastAPI는 외부에 공개되지 않는 전용 Docker 네트워크·서비스 인증·TLS와 AI 전용 권한으로 DB의 `ai` 스키마에만 접근한다.
- provider/검색/색인 장애 때는 같은 run에서 검증된 canonical 기본 탐색으로 완료할 수 있다. 구 revision의 철회 문서를 근거로 사용하는 것은 금지한다.
- 품질 개선 agent는 사용자 요청의 온라인 도구 선택자가 아니라 비동기 평가·원인 조사·실험 제안 작업자다. 평가 엔진과 격리 runner는 결정적으로 실행하고, 운영 설정·corpus 승격은 사람 승인 절차를 거친다. [상세 계약](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/quality-engineering-agent.md)을 따른다.

## 대안과 영향

기존 baseline-first optional RAG는 운영 부담이 작지만, 새 홈 대화의 설명 깊이와 공개 본문 근거 요구를 기본 경험으로 충족하지 못한다. Elasticsearch/OpenSearch는 한국어 전문 검색과 확장성이 강하지만 단일 2GB 서버와 예산에서 별도 JVM/관리형 비용을 정당화할 근거가 없다. PostgreSQL BM25 확장은 별도 엔진 없이 도입할 수 있으나 한국어 tokenizer·라이선스·PostGIS 병행·메모리 검증이 먼저 필요하다.

장점은 canonical 필터와 검색·출처를 한 데이터베이스 경계에서 운영하고, 모델이 장소를 임의로 선택하지 못하게 할 수 있다는 점이다. 비용은 코퍼스 revision 관리, 임베딩·색인, DB 용량, 다턴 상태·평가 운영이 늘어나는 것이다.

## 확인과 재검토

공개/철회 위반 0, BM25·vector·RRF·reranker 단계별 한국어 retrieval 품질, 인덱스 크기, 정상 API 지연, 2GB 서버 메모리·OOM, 내부 DB 연결을 staging에서 검증한다. 품질이나 자원 게이트를 넘지 못하면 BM25 구현·reranker 실행 자원·색인/배치 용량을 재설계해 2GB에서 재시험한다. 미통과 상태로 출시하거나 서버를 자동 증설하지 않는다. 확인되지 않은 오래된 근거는 제공하지 않는다. 세부 제품·파이프라인 기준은 [새 설계](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/README.md)를 따른다.
