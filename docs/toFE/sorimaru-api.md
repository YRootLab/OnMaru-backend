# 소리마루 Single Source of Truth API

세 API는 프론트가 Odii 제공기관을 직접 호출하지 않고 OnMaru backend만 호출하기 위한 호환 API다. 응답은 기존 Odii 목록의 `schemaVersion`, `coverageStatus`, `language`, `languageStatus`, `items`, `nextCursor`, `hasMore` 구조를 사용한다. 현재는 Redis 캐시를 사용하지 않으며 `Cache-Control: no-store`를 반환한다.

## 스토리 검색

`GET /api/stories?keyword=한옥&language=ko-KR&limit=20`

- `keyword`: 필수, 1~80자. story 제목, 오디오 제목, contentTags의 부분 일치
- `language`: 선택, 기본 `ko-KR`; 요청 언어가 없으면 한국어 fallback
- `limit`: 선택, 1~50, 기본 `20`

## 근처 스토리

`GET /api/stories/nearby?lat=35.817632&lng=127.152948&radius=5000&limit=20`

- `lat`: 필수, -90~90
- `lng`: 필수, -180~180
- `radius`: 선택, 미터 단위 1~50,000, 기본 `5000`
- `language`, `limit`: 검색 API와 동일
- 결과는 Haversine 거리 오름차순이며, 거리가 같으면 게시일 최신순이다.

## 키워드 추천

`GET /api/recommendation?keyword=궁궐&language=ko-KR&limit=20`

- `keyword`: 필수, 1~80자
- 제목 exact match, 제목 부분 일치, 오디오 제목, contentTags 순으로 점수화한다.
- 동점은 게시일 최신순과 story ID 순으로 정렬한다.
- 외부 추천 서비스나 사용자별 캐시는 사용하지 않는다.

## 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | keyword, 좌표, 반경, language, limit 오류 |
| 503 | `SERVICE_UNAVAILABLE` | active Odii dataset이 없거나 조회 불가 |

`items`가 비어도 정상적인 `200` 응답이며 가짜 카드를 반환하지 않는다.
