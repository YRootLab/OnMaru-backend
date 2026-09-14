# R1 한옥·장소·찜 계약 동결 설계

## 목표

Issue #62의 범위에 맞춰 Spring과 FE가 공유할 R1 기계 계약을 문서와 fixture로 동결한다. 구현 endpoint는 만들지 않는다.

## 계약 범위

- `GET /hanoks`: 한옥 목록 검색·필터·cursor 페이지.
- `GET /hanoks/{placeId}`: 한옥 상세. 공개 식별자는 canonical `placeId`만 사용한다.
- `GET /hanoks/monthly`: 월별 한옥 editorial edition.
- `GET /places/{placeId}`: 한옥, 지도, Odii 연결 카드가 공유하는 canonical 장소 상세.
- `PUT/DELETE /saved-resources/places/{placeId}`: 관광 장소 찜 상태 설정과 해제.
- `GET /saved-resources?type=PLACE`: 장소 찜 목록.
- `GET /me/timeline`: 월별 개인 활동 타임라인.

## 데이터 원칙

공개 schema와 fixture에는 provider 원본 식별자인 `contentId`, `pageNo`, `key`, `serviceKey`를 넣지 않는다. 한옥 카드, 지도 카드, Odii 연결 장소 카드는 같은 `placeId`를 공유해야 한다. 개인 응답은 `Cache-Control: no-store` 정책을 문서화한다.

## 오류와 예제

R1 fixture는 정상, 빈 목록, 404, cursor, auth, unavailable, service unavailable 사례를 포함한다. 모든 오류는 기존 공통 `{schemaVersion, code, message, requestId, details}` envelope를 따른다.

## 검증

`scripts/test/validate-r1-contract.py`가 OpenAPI 3.1 문서와 `docs/contracts/fixtures/r1/*.json`을 읽어 필수 path/schema, fixture 이름, endpoint/status별 schema 참조, provider key 비노출, 공유 `placeId`를 검증한다. 표준 OpenAPI 구조는 `openapi-spec-validator`로 확인하고, fixture response body는 `jsonschema` Draft 2020-12로 OpenAPI component schema에 대입해 검증한다.
