# Contracts

브라우저와 Spring 사이의 공개 계약, 그리고 Spring과 FastAPI 사이에서 유지해야 할 typed boundary를 보조한다. 구현 전에는 계약 초안이며, 실제 endpoint가 아니다.

- [Public REST API](rest-api.md): 여정, 저장, 오류, idempotency, VisitReview 계약
- [Frontend handoff](frontend-handoff.md): FE 반영 범위와 전환 체크리스트
- [`openapi/journey.openapi.yaml`](openapi/journey.openapi.yaml): AI 여정 REST command/snapshot/SSE endpoint OpenAPI 3.1 초안
- [`openapi/r1.openapi.yaml`](openapi/r1.openapi.yaml): R1 한옥·장소·찜·월간 타임라인 OpenAPI 3.1 초안
- [`openapi/identity-saved.openapi.yaml`](openapi/identity-saved.openapi.yaml): 인증·회원·SavedResource·월간 타임라인 OpenAPI 3.1 초안
- [`openapi/r2-map-audio-insights.openapi.yaml`](openapi/r2-map-audio-insights.openapi.yaml): R2 지도·행정구역·Odii·관광 관측 OpenAPI 3.1 초안
- [`schemas/journey-sse-event.schema.json`](schemas/journey-sse-event.schema.json): 여정 진행 알림 event data JSON Schema
- [`fixtures/journey-sse-fixtures.json`](fixtures/journey-sse-fixtures.json): reconnect/reset/cancel contract fixture 목록
- [`fixtures/r1`](fixtures/r1): R1 한옥·장소·찜 정상·빈·404·cursor·auth·unavailable 예제
- [`fixtures/identity-saved`](fixtures/identity-saved): 인증 redirect/no-store, 회원 lifecycle, PLACE/ODII_STORY 저장 정상·권한·멱등 예제
- [`fixtures/r2`](fixtures/r2): R2 지도·행정구역·Odii·관광 관측 정상·결측·error 예제
- [`fixtures/saved-place-fixtures.json`](fixtures/saved-place-fixtures.json): 한옥·지도·Odii 연결 장소의 공통 찜 및 월간 타임라인 fixture
- [`openapi/visit-reviews.openapi.json`](openapi/visit-reviews.openapi.json): 지도 후기 OpenAPI 3.1 초안

구현 전 게이트: OpenAPI와 fixture를 실제 FE/BE serializer contract test로 연결하고, pagination/cursor, 인증 오류와 AI typed output을 검증한다.
