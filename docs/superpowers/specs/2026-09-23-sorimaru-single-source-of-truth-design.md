# 소리마루 Single Source of Truth API 설계

## 목표

기존 Odii active revision 조회 서비스를 재사용해 프론트가 요구하는 스토리 검색, 근처 스토리, 키워드 추천 API를 백엔드 단일 데이터 원천으로 제공한다. Redis와 외부 Whale.Be 호출은 이번 범위에서 제외한다.

## API

- `GET /api/stories?keyword=&language=&limit=`: 제목, 오디오 제목, 콘텐츠 태그를 대상으로 대소문자·공백을 정규화한 부분 검색을 수행한다.
- `GET /api/stories/nearby?lat=&lng=&radius=&language=&limit=`: WGS84 좌표와 반경(미터)을 검증하고 Haversine 거리순으로 공개 가능한 스토리를 반환한다.
- `GET /api/recommendation?keyword=&language=&limit=`: 검색 일치도를 우선하고 게시일과 story ID를 안정적인 tie-breaker로 사용하는 결정론적 추천을 반환한다.

세 API는 기존 `OdiiStoryPage` 응답 구조를 사용하며 `nextCursor=null`, `hasMore=false`인 비커서형 응답이다. 기존 `/api/v1/odii/stories`, `/api/v1/home/*` 계약은 변경하지 않는다.

## 내부 구조

`OdiiStoryQueryService`에 검색·근처·추천을 추가한다. 모든 경로는 active snapshot, 공개/재생 가능 판정, 언어 exact/ko-KR fallback, 중복 제거, 안전한 이미지 URL, 회원 저장 상태 조회를 기존 로직과 공유한다. 컨트롤러는 파라미터 검증 오류를 `400 INVALID_REQUEST`, active 데이터 부재를 `503 SERVICE_UNAVAILABLE`으로 변환한다.

## 검증 규칙

- `keyword`: 필수, trim 후 1~80자
- `language`: 기존 언어 패턴 사용
- `limit`: 1~50
- `lat`: -90~90, `lng`: -180~180
- `radius`: 기본 5,000m, 1~50,000m

## 테스트

서비스 단위 테스트로 검색 필드, 거리순 정렬, 추천 점수 정렬, 입력 검증, 언어 fallback을 검증한다. Spring MockMvc 경계 테스트로 세 경로의 200/400/503 응답과 기존 보안·공개 데이터 규칙 유지를 검증한다.
