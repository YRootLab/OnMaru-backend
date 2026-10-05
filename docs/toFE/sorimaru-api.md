# 소리마루 Single Source of Truth API

프론트는 Odii 제공기관 대신 OnMaru backend를 호출한다. 아래 테마 목록과 검색·근처 스토리·추천 API는 기존 Odii 목록의 `schemaVersion`, `coverageStatus`, `language`, `languageStatus`, `items`, `totalCount`, `nextCursor`, `hasMore` 구조를 사용한다. `totalCount`는 현재 필터를 적용한 전체 결과 건수이며 `items.length`와 다를 수 있다. 현재는 Redis 캐시를 사용하지 않으며 `Cache-Control: no-store`를 반환한다.

## 소리로 만나는 한국 테마 탭

`GET /api/v1/odii/stories?category=HANOK_HERITAGE&language=ko-KR&limit=20`

| 화면 탭 | `category` 코드 | 기존 FE 요청 호환값 |
| --- | --- | --- |
| 전체 보기 | 생략 | 생략 |
| 한옥과 고택 | `HANOK_HERITAGE` | `한옥` |
| 전통 시장 | `TRADITIONAL_MARKET` | `시장` |
| 마을과 골목 | `VILLAGE_STREETS` | `마을` |
| 궁궐과 역사 | `PALACE_HISTORY` | `궁` |
| 소리와 문화 | `SOUND_CULTURE` | `소리` |
| 자연과 숲길 | `NATURE_TRAILS` | `길` |

FE는 화면 문구와 무관한 코드값을 보내야 한다. 탭 이름을 바꿀 때는 API 코드를 바꾸지 않는다. 기존 한국어 요청도 전환 기간에 지원한다. BE는 이야기 제목과 콘텐츠 태그를 우선 사용하고, 제목이 단순한 `소개`·`이야기 1` 같은 경우에만 장소명으로 보완한다. 테마별로 고택·고분·탈춤·습지처럼 관련성이 분명한 용어를 포함하며, `궁`·`길` 같은 한 글자 부분 일치로 분류하지 않는다. 하나의 이야기가 여러 테마에 속하면 각 탭에 표시한다. `totalCount`는 현재 테마에 맞는 고유 이야기 수이고 cursor 페이지에도 같은 분류 조건이 적용된다. 전체 보기에서는 각 이야기를 한 번만 보여준다. 데이터에 근거한 일치 항목이 없으면 `items=[]`, `totalCount=0`을 반환한다. 표시용 `items[].category`는 단일 대표 분류이므로 테마 소속 전체를 나타내지는 않는다.

이 테마 탭에는 `regionCode`를 보내지 않는다. 지역 탐색은 별도의 지도/지역 화면에서 처리한다.

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
- 키워드에 일치하는 story만 후보로 삼고 제목 exact match, 제목 부분 일치, 오디오 제목, contentTags 순으로 점수화한다.
- 동점은 게시일 최신순과 story ID 순으로 정렬한다.
- 외부 추천 서비스나 사용자별 캐시는 사용하지 않는다.

## 오류

| 상태 | code | 상황 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | keyword, 좌표, 반경, language, limit 오류 |
| 503 | `SERVICE_UNAVAILABLE` | active Odii dataset이 없거나 조회 불가 |

`items`가 비어도 정상적인 `200` 응답이며 가짜 카드를 반환하지 않는다.
