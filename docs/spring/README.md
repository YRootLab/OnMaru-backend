# Spring Boot 설계

- [Kakao OAuth 로그인·로그아웃 흐름](kakao-oauth-flow.md): 로그인 시퀀스, 이후 인증, 카카오 콘솔 Redirect/Logout URI 등록

Spring Boot는 공개 비즈니스 API와 PostgreSQL business persistence, 외부 관광 데이터 수집을 소유한다. FastAPI의 provider adapter 또는 prompt 내용을 소유하지 않는다.

- [Identity and journey](identity-and-journey.md): 회원, OAuth 연결, 소유권, 저장 여정, 보존 정책
- [Catalog ingestion](catalog-ingestion.md): 한국관광공사/Odii 수집, 지역 목록, revision 공개, 동기화 복구

구현 전 게이트: 실제 API 응답과 rate limit 확인, migration 범위, OAuth callback/CSRF, idempotency와 ownership fixture를 확정한다.
