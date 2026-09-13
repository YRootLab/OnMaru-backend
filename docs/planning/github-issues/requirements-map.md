# Backend 요구사항–Issue 추적표

FE 컴포넌트 구현과 `docs/toFE/**`는 제외한다. 공개 OpenAPI·fixture·serializer 호환성은 backend 책임으로 포함한다.

| 요구 영역 | 기준 문서 | Leaf Issue |
|---|---|---|
| BE-REQ-001~003 한옥 검색·상세·큐레이션 | `backend-prd.md`, `data-api-design.md` | C01~C06 |
| BE-REQ-004~005 지도 장소·지역·관측 | `backend-prd.md`, `catalog-ingestion.md` | C05, M01, P06 |
| BE-REQ-006~007 VisitReview·좋아요·신고 | `rest-api.md`, `moderation.md` | D05, M02~M05 |
| BE-REQ-008 Odii·언어·대본·장소 연결 | `data-api-design.md`, `catalog-ingestion.md` | D06, A01~A04 |
| BE-REQ-009 AI 여정 | `application-architecture.md`, `journey-guardrails.md`, Journey OpenAPI | AI01~AI06, J01~J10 |
| BE-REQ-010 공통 관광 장소 찜 | `rest-api.md`, `identity-and-journey.md` | I05~I07 |
| Spring/FastAPI 런타임과 모듈 경계 | ADR-0002~0005, `module-boundaries.md` | F01~F06, AI01 |
| PostgreSQL/PostGIS·migration·동시성 | ADR-0004, `schema.dbml`, `migration-and-concurrency.md` | D01~D08 |
| TourAPI 실제 응답·quota·license | `catalog-ingestion.md`, `docs/api/tour/**` | P01~P05 |
| canonical identity·revision·LKG 게시 | ADR-0006, `catalog-ingestion.md` | D02, D07, P03~P05 |
| Kakao·opaque session·guest grant·CSRF | ADR-0008, `identity-and-journey.md` | D03, I01~I04 |
| REST command·SSE·snapshot·멱등성 | ADR-0005, `rest-api.md` | F06, J01~J10 |
| baseline·Gemini·guardrail·평가 | ADR-0007, `journey-guardrails.md` | AI02~AI06 |
| optional corpus sync·RAG | `corpus-sync-contract.md`, `retrieval-and-rag.md` | AI07~AI09 |
| 보안·secret·보존·탈퇴 | `identity-and-journey.md`, `runtime-and-reliability.md` | I03~I04, O03, O08 |
| 관측성·Grafana·moderation | ADR-0009, `runtime-and-reliability.md`, `moderation.md` | M05, O01~O02 |
| migration/backup/restore/load/outage/release | `migration-and-concurrency.md`, `runtime-and-reliability.md` | D08, O04~O09 |

현재 제외: 예약·결제·소셜 관계·현장 체크인·정-길·비밀번호 로그인·관리자 UI·모델 선택 tool calling·웹 검색·개인화 추천. 이 항목은 현재 PRD의 명시적 Out of Scope이므로 구현 Leaf를 만들지 않는다.
