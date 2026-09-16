# OnMaru FE 전달 패키지

`docs/specs`는 기존 FE 저장소에서 참조하는 원본 요구사항이며 이 저장소에서 직접 수정하지 않는다. 이 폴더는 그 문서에 없었거나 계약이 바뀐 백엔드 기능을 FE가 반영하기 위한 **변경 전달 패키지**다. 구현 완료·배포 완료를 뜻하지 않는다.

## 읽는 순서

1. [Feature delta](feature-delta.md): 기존 `docs/specs` 대비 화면·기능 변화와 사용자 경험 규칙
2. [Content tags](content-tags.md): 관광지·한옥·Odii 자체 추출 태그 필드와 렌더링 규칙
3. [Integration checklist](integration-checklist.md): 라우트별 API, 상태, fixture, 완료 조건
4. 기계 계약: [Journey OpenAPI](../contracts/openapi/journey.openapi.yaml), [VisitReview OpenAPI](../contracts/openapi/visit-reviews.openapi.json), [SSE schema](../contracts/schemas/journey-sse-event.schema.json), [SSE fixtures](../contracts/fixtures/journey-sse-fixtures.json), [관광 장소 찜 fixture](../contracts/fixtures/saved-place-fixtures.json)

정확한 API 필드와 오류 코드는 [Public REST API](../contracts/rest-api.md)가 정답이다. 이 폴더는 그 계약을 FE 작업 흐름으로 번역하며, 필드가 충돌하면 `docs/contracts`를 따른다.

## 이번 패키지 범위

- `/discover` AI 여정: REST command + SSE notification + snapshot 복구, Gemini 실패 시 BASELINE 결과
- `/map` VisitReview: 행정구역 집계, 명시적 목록 조회, 짧은 후기·좋아요·신고
- 한옥·지도·Odii 연결 장소의 공통 찜과 내 정보 월간 타임라인
- 관광지·한옥·Odii 상세/요약의 `contentTags`
- Kakao 로그인, CSRF, opaque ID, 개인 API cache 규칙

한옥 도감, 기존 오디오 재생, Kakao map rendering, 랜딩의 3D 경험은 FE 기존 책임을 유지한다. 이 패키지는 그 UI를 교체하지 않는다.
