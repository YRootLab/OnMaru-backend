# Backend 요구사항–Issue 추적표

FE 컴포넌트 구현과 `docs/toFE/**`는 제외한다. 공개 OpenAPI·fixture·serializer 호환성은 backend 책임으로 포함한다.

| 요구 영역 | 기준 문서 | Leaf Issue |
|---|---|---|
| BE-REQ-001~003 한옥 검색·상세·큐레이션 | `backend-prd.md`, `data-api-design.md` | C01~C06 |
| BE-REQ-004 지도 장소·행정구역 | `backend-prd.md`, `catalog-ingestion.md` | C05, P07, M01, M06, M09 |
| BE-REQ-005 방문자·집중률 히트맵 | `backend-prd.md`, `catalog-ingestion.md`, insights DBML | D09, P06, M06~M07, M09 |
| BE-REQ-006~007 VisitReview·좋아요·신고 | `rest-api.md`, `moderation.md` | D05, M02~M05, M08~M09 |
| BE-REQ-008 Odii·언어·대본·장소 연결 | `data-api-design.md`, `catalog-ingestion.md` | D06, A01~A04, M06, M09 |
| BE-REQ-009 AI 여정 | `application-architecture.md`, `journey-guardrails.md`, Journey OpenAPI | AI01~AI06, J01~J11 |
| BE-REQ-010 공통 관광 장소 찜 | `rest-api.md`, `identity-and-journey.md` | F09, I05~I07, C06, M09 |
| Spring/FastAPI 런타임과 모듈 경계 | ADR-0002~0005, `module-boundaries.md` | F01~F06, AI01 |
| Backend 입력 문서 재현성 | `docs/specs`, `backend_schema_design_guide.md` | F07 |
| 공개 API OpenAPI·fixture | `rest-api.md`, `docs/contracts/openapi/**` | C01, F09, M06, J11, C06, M09, J10 |
| PostgreSQL/PostGIS·migration·동시성 | ADR-0004, `schema.dbml`, `migration-and-concurrency.md` | D01~D09 |
| TourAPI 실제 응답·quota·license | `catalog-ingestion.md`, `docs/api/tour/**` | P01~P05 |
| canonical identity·revision·LKG 게시 | ADR-0006, `catalog-ingestion.md` | D02, D07, P03~P05 |
| Kakao·opaque session·guest grant·CSRF | ADR-0008, `identity-and-journey.md` | D03, F09, I01~I04 |
| REST command·SSE·snapshot·멱등성 | ADR-0005, `rest-api.md` | F06, F09, J01~J11 |
| 공개 command rate-limit·admission | `rest-api.md`, `identity-and-journey.md`, operations DBML | F08, I01, M03, M05, J01, J09 |
| baseline·Gemini·guardrail·평가 | ADR-0007, `journey-guardrails.md` | AI02~AI06 |
| optional corpus sync·RAG | `corpus-sync-contract.md`, `retrieval-and-rag.md` | AI07~AI09 |
| 보안·secret·보존·탈퇴 | `identity-and-journey.md`, `runtime-and-reliability.md` | I03~I04, O03, O08 |
| 관측성·Grafana·moderation | ADR-0009, `runtime-and-reliability.md`, `moderation.md` | M05, M08, O01~O02 |
| migration/backup/restore/load/outage/release | `migration-and-concurrency.md`, `runtime-and-reliability.md` | D08, M09, O04~O09 |

현재 제외: 예약·결제·소셜 관계·현장 체크인·정-길·비밀번호 로그인·관리자 UI·모델 선택 tool calling·웹 검색·개인화 추천. 이 항목은 현재 PRD의 명시적 Out of Scope이므로 구현 Leaf를 만들지 않는다.
