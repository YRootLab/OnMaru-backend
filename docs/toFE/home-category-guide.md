# 홈 화면 관광 카테고리 확장 FE 연동 가이드

> 문서 기준일: **2026-09-21**  
> 문서 성격: OnMaru FE 연동 이슈 작성용 handoff  
> 관련 BE 이슈: [#246 문화재](https://github.com/YRootLab/OnMaru-backend/issues/246)  
> 관련 BE 구현: PR #282 홈 카탈로그·API 확장, PR #292 홈 API canonical 경로 정리

이 문서는 `onmaru-frontend-issue`에 FE 작업 이슈를 만들 때 사용할 수 있는
실행 기준서다. 전문 용어보다 화면에서 무엇을 바꾸고 어떤 효과를 얻는지를
먼저 설명한다. 모든 API 응답은 JSON이며 `Cache-Control: no-store`를 사용한다.

## 0. 이번 변경의 목적과 기대 효과

기존 홈 화면은 한옥·역사·자연·전통시장 중심의 적은 카드만 보여 주었다.
이번 BE 확장은 한옥과 직접 연결되는 숙박, 문화예술, 전통음식, 정원·생태,
지역 생활 장면까지 같은 장소 목록 계약으로 제공한다. 레저·스포츠는 이번
확장 대상에서 제외한다.

FE가 이 문서를 기준으로 연동하면 다음 효과를 기대할 수 있다.

- 홈 첫 화면에서 사용자가 자신의 관심사에 맞는 콘텐츠를 더 쉽게 찾는다.
- “한옥을 보고, 먹고, 머물고, 체험하는” 탐색 흐름이 한 화면에서 이어진다.
- 하드코딩된 목데이터와 카테고리 배열을 줄이고 실제 API 데이터로 전환한다.
- 장소 카드, 상세, 지도, 저장(찜)이 동일한 `placeId`를 사용해 화면 간 상태가
  어긋나지 않는다.
- 카테고리와 지역을 조합해 “전주 한옥 숙박”, “서울 역사·문화유산”처럼
  구체적인 탐색 UI를 만들 수 있다.

### FE 이슈에 포함할 작업 범위

1. 홈 추천 장소 API를 실제 호출하고 기존 목데이터를 제거한다.
2. `HANOK_STAY`, `CULTURE_ART`, `TRADITIONAL_FOOD`, `GARDEN_ECOLOGY`,
   `LOCAL_SCENE` 카테고리를 필터·배지·접근성 이름에 추가한다.
3. `LEISURE_ACTIVITY`는 홈 메뉴와 필터에 노출하지 않는다.
4. 카드의 `placeId`를 상세·지도·저장 API에 연결한다.
5. 빈 목록, 이미지 없음, cursor, 400/401/404/503 상태를 화면별로 처리한다.
6. 인기 오디오와 인기 지역 API도 같은 기준으로 목데이터를 교체한다.

### BE에서 새로 추가하거나 확장한 것

새로운 최상위 URL을 만든 것이 아니라 기존 홈 API의 **카테고리와 데이터
범위를 확장**했다.

| 구분 | 제공 내용 |
| --- | --- |
| 추천 장소 | 기존 `GET /api/v1/home/curated-courses`에 5개 카테고리와 한옥 숙박 카드 추가 |
| 인기 오디오 | `GET /api/v1/home/trending-sounds`의 장소·카테고리 연결 정보 사용 |
| 인기 지역 | `GET /api/v1/home/popular-regions`의 지역 집계 사용 |
| 검색 탐색 | `POST /api/v1/explorations` 또는 호환 경로로 확장된 장소 탐색 시작 |
| 저장 | 장소·오디오를 사용자별로 저장/해제하고 저장 목록 조회 |
| 데이터 품질 | 검수된 TourAPI 코드만 공개하고 미검수 분류는 격리 |

### 이번 범위에 포함하지 않는 것

- 레저·스포츠를 홈 공개 카테고리로 추가하지 않는다.
- 축제·공연 일정처럼 날짜와 행사 상태가 핵심인 데이터는 이번 장소 API에
  억지로 섞지 않는다.
- FE에서 관광공사 원천 카테고리 코드(`cat1`, `cat2`, `cat3`)를 직접 해석하지
  않는다. FE는 아래 canonical category 값만 사용한다.

## 1. 홈 화면에서 사용할 API

| 화면 기능 | API | 로그인 필요 |
| --- | --- | --- |
| 이번 주 추천 장소 카드 | `GET /api/v1/home/curated-courses` | 아니오 |
| 인기 한옥 소리 카드 | `GET /api/v1/home/trending-sounds` | 아니오 |
| 인기 지역 목록 | `GET /api/v1/home/popular-regions` | 아니오 |
| 검색 질문으로 여행 탐색 시작 | `POST /api/v1/explorations` 또는 `/api/journey-curator/explore` | 세션 유지 필요 |
| 장소 저장/찜 | `PUT /api/v1/saved-resources/places/{placeId}` | 예 |
| 장소 저장 취소 | `DELETE /api/v1/saved-resources/places/{placeId}` | 예 |
| 오디오 저장/찜 | `PUT /api/v1/saved-resources/odii-stories/{storyId}` | 예 |
| 내 저장 목록 | `GET /api/v1/saved-resources?type=PLACE` 또는 `ODII_STORY` | 예 |

운영 API의 기본 주소는 `https://onmaru-backend.onrender.com`이다. 브라우저
요청에는 로그인 상태와 저장 상태를 유지할 수 있도록 `credentials: "include"`를
사용한다.

## 2. 추천 장소 목록

### 요청

```text
GET /api/v1/home/curated-courses?limit=20&category=HISTORIC_SITE
```

선택할 수 있는 query parameter는 다음과 같다.

| 이름 | 의미 |
| --- | --- |
| `limit` | 한 번에 받을 카드 수. 1~50, 기본값 20 |
| `cursor` | 다음 페이지를 요청할 때 `nextCursor`를 그대로 전달 |
| `category` | 아래 카테고리 중 하나 |
| `regionCode` | 지역 코드로 좁혀 보기 |
| `keyword` | 장소 이름 또는 설명에 포함된 단어로 검색 |
| `hasImage` | `true`이면 썸네일이 있는 카드만 요청 |

`keyword=한옥`으로 검색하면 이름이나 설명에 한옥이라는 글자가 직접 없어도
한옥 숙박·한옥 카페·한옥 체험·전통시장·문화예술·전통음식·지역 생활처럼
한옥 여행과 연결된 카테고리의 장소를 함께 찾는다. 특정 카테고리를 함께
보내면 그 카테고리 안에서만 검색한다. FE가 여러 카테고리를 직접 조합하거나
관광공사 원천 코드를 해석할 필요는 없다.

### 카테고리 목록

| 값 | 쉬운 설명 | 화면 예 |
| --- | --- | --- |
| `HANOK` | 한옥 건물·한옥 마을 | 전주 한옥마을 |
| `HANOK_STAY` | 한옥 숙박 | 한옥스테이 |
| `HANOK_CAFE` | 한옥 카페·찻집 | 북촌 한옥 찻집 |
| `HANOK_EXPERIENCE` | 한옥과 연결된 전통 체험 | 전통문화 체험 |
| `TRADITIONAL_MARKET` | 전통시장과 지역 장터 | 전주 남부시장 |
| `HISTORIC_SITE` | 궁궐·성곽·유적·사찰 등 역사 장소 | 경복궁 |
| `NATURE_SITE` | 일반 자연 관광지 | 설악산 |
| `CULTURE_ART` | 검수된 박물관·미술관·전시관 | 문화 전시관 |
| `TRADITIONAL_FOOD` | 검수된 한식·전통 음식 장소 | 지역 한식당 |
| `GARDEN_ECOLOGY` | 자연생태·휴양림·수목원 | 수목원 |
| `LOCAL_SCENE` | 농촌 체험·관광농원 등 지역 생활 장면 | 농촌 체험장 |

`LEISURE_ACTIVITY`는 원천 데이터의 내부 분류로는 유지되지만 홈의 공개
카테고리와 추천 장소 필터에는 포함되지 않는다. FE의 카테고리 선택 메뉴에도
추가하지 않는다.

### 응답 필드

```json
{
  "schemaVersion": "1.2",
  "items": [
    {
      "placeId": "p-gyeongbokgung",
      "name": "경복궁",
      "category": "HISTORIC_SITE",
      "regionName": "서울 종로구",
      "thumbnailUrl": null,
      "summary": "조선 왕조의 정궁과 궁궐 건축을 함께 만나는 대표 역사 문화유산입니다.",
      "tags": ["문화재", "궁궐", "역사", "산책"],
      "saved": false
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

| 필드 | 의미 | 화면에서의 사용 |
| --- | --- | --- |
| `schemaVersion` | 응답 형식 버전 | 보통 표시하지 않음 |
| `items` | 장소 카드 배열 | 배열 길이만큼 카드 렌더링 |
| `placeId` | 장소의 고정 식별자 | 상세 이동과 저장 API의 path에 사용 |
| `name` | 장소 이름 | 카드 제목 |
| `category` | 위 카테고리 값 | 배지·필터·분석 이벤트 |
| `regionName` | 사람이 읽는 지역명 | 카드의 위치 표시 |
| `thumbnailUrl` | 썸네일 주소, 없으면 `null` | `null`이면 공통 placeholder |
| `summary` | 장소를 짧게 설명하는 문장 | 카드 설명 |
| `tags` | 장소를 설명하는 보조 단어 배열 | 태그 칩 |
| `saved` | 현재 로그인 회원이 저장했는지 여부 | 하트의 선택 상태 |
| `nextCursor` | 다음 페이지용 값, 없으면 `null` | `hasMore`가 true일 때만 사용 |
| `hasMore` | 다음 페이지 존재 여부 | 무한 스크롤 또는 더 보기 버튼 |

장소 상세 화면으로 이동할 때는 응답의 `placeId`를 사용한다. 카드 수를 25개로
고정하지 말고 `items`와 `hasMore`를 기준으로 그린다.

```ts
const response = await fetch(
  `${BACKEND_URL}/api/v1/home/curated-courses?limit=20`,
  { credentials: "include" }
);
const page = await response.json();
const cards = page.items ?? [];
```

## 3. 인기 한옥 소리

### 요청

```text
GET /api/v1/home/trending-sounds?language=ko-KR&limit=20
```

`language`의 기본값은 `ko-KR`이며 `category`, `regionCode`, `limit`, `cursor`를
추가할 수 있다. 응답의 `items`, `nextCursor`, `hasMore`는 장소 목록과 같은
페이지 처리 방식으로 사용한다.

### 응답 예시와 필드

```json
{
  "schemaVersion": "1.2",
  "coverageStatus": "READY",
  "language": "ko-KR",
  "languageStatus": "AVAILABLE",
  "items": [
    {
      "storyId": "story-gyeongbokgung-01",
      "title": "경복궁의 하루",
      "audioTitle": "궁궐 건축 이야기",
      "category": "HISTORIC_SITE",
      "region": { "regionCode": "kr-11-jongno", "name": "서울 종로구" },
      "coordinates": { "lat": 37.5788, "lng": 126.9770 },
      "durationSeconds": 184,
      "imageUrl": null,
      "linkedPlaceId": "p-gyeongbokgung",
      "contentTags": ["궁궐", "역사"],
      "savedByMe": false
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

`storyId`는 오디오 저장에 사용하고, `linkedPlaceId`가 있으면 장소 저장에는
그 값을 사용한다. `durationSeconds`는 재생 시간(초), `imageUrl`은 없을 수
있으며, `contentTags`는 설명용 태그다. 재생 버튼은 `storyId`를 기준으로
연결하고, 장소 상세 이동은 `linkedPlaceId`가 있을 때만 제공한다.

## 4. 인기 지역

### 요청

```text
GET /api/v1/home/popular-regions
GET /api/v1/home/popular-regions?parentRegionCode=11
```

응답은 방문 후기 수를 지역별로 묶은 데이터다.

```json
{
  "schemaVersion": "1.2",
  "regionRevision": "2026-09-14",
  "countsAsOf": "2026-09-14T08:00:00Z",
  "parentRegionCode": "11",
  "unassignedCount": 0,
  "items": [
    {
      "region": {
        "regionCode": "kr-11-jongno",
        "name": "서울 종로구",
        "parentRegionCode": "11",
        "level": "DISTRICT"
      },
      "reviewCount": 42
    }
  ]
}
```

`reviewCount`가 큰 순서로 보여 주거나 서버가 준 순서를 유지한다. 지역명과
지역 코드는 FE에 하드코딩하지 말고 `region` 객체를 사용한다.

## 5. 검색창과 탐색 시작

```http
POST /api/v1/explorations
Content-Type: application/json
Idempotency-Key: unique-request-key
```

호환 경로인 `POST /api/journey-curator/explore`도 같은 요청·응답 계약을
사용한다.

```json
{
  "query": "경주에서 한옥과 자연을 함께 보고 싶어요",
  "locale": "ko-KR",
  "regionCode": "kr-47-gyeongju"
}
```

정상 응답은 탐색을 시작했다는 뜻의 `202`이며, 응답의
`explorationId`, `runId`, `snapshotUrl`을 저장한 뒤 기존 polling/SSE 화면으로
연결한다. 쿠키 인증을 사용하므로 `credentials: "include"`를 적용하고,
필요한 경우 CSRF 토큰도 함께 보낸다.

## 6. 저장(찜) API와 카드 연결

### 장소 저장

추천 장소 카드의 `placeId`로 호출한다.

```text
PUT /api/v1/saved-resources/places/p-gyeongbokgung
```

성공하면 다음 조회에서 해당 카드의 `saved`가 `true`가 된다. 저장 취소는 다음
호출로 한다.

```text
DELETE /api/v1/saved-resources/places/p-gyeongbokgung
```

장소 저장은 로그인 회원만 가능하다. 비회원이 하트를 누르면 로그인 화면으로
보내고, 로그인 성공 후 원래 `placeId`에 대해 PUT을 한 번 호출한다. 로그인
취소나 실패 시에는 저장 API를 호출하지 않는다.

### 오디오 저장

오디오 카드의 `storyId`로 호출한다.

```text
PUT /api/v1/saved-resources/odii-stories/story-gyeongbokgung-01
DELETE /api/v1/saved-resources/odii-stories/story-gyeongbokgung-01
```

장소와 오디오 이야기는 별도로 저장된다. 오디오에 `linkedPlaceId`가 있더라도
오디오 저장은 `storyId`, 장소 저장은 `linkedPlaceId`를 사용한다.

### 내 저장 목록

```text
GET /api/v1/saved-resources?type=PLACE&limit=20
GET /api/v1/saved-resources?type=ODII_STORY&limit=20
```

저장 목록은 로그인 회원 전용이며 `nextCursor`와 `hasMore`를 사용한다. `PLACE`
항목은 `placeId`, `name`, `category`, `regionName`, `thumbnailUrl`, `savedByMe`,
`savedAt`을 제공하고, `ODII_STORY` 항목은 `storyId`, `title`, `placeId`,
`durationSeconds`, `savedByMe`, `savedAt`을 제공한다.

## 7. 빈 데이터와 오류 처리

| 상황 | 처리 방법 |
| --- | --- |
| `items: []` | “아직 준비된 콘텐츠가 없어요” 상태를 표시. 임의의 가짜 카드를 만들지 않음 |
| `thumbnailUrl`·`imageUrl`이 `null` | 공통 이미지 placeholder 표시 |
| `hasMore: true` | `nextCursor`를 다음 요청에 그대로 전달 |
| `400 INVALID_REQUEST` | 필터·limit·category를 확인하고 사용자에게 재시도 안내 |
| `CURSOR_INVALID` | 기존 목록을 첫 페이지부터 다시 요청 |
| `CURSOR_EXPIRED` | 오래된 커서를 버리고 첫 페이지부터 다시 요청 |
| `401 AUTH_REQUIRED` | 저장 동작에서 로그인 화면으로 이동 |
| `404 NOT_FOUND` | 장소/스토리가 더 이상 공개되지 않는 상태로 처리 |
| `409 SAVE_LIMIT` | 저장 한도 초과 안내 |
| `503 SERVICE_UNAVAILABLE` | 빈 성공 데이터로 바꾸지 말고 준비 중/재시도 UI 표시 |

모든 오류 응답의 `code`와 `message`를 기준으로 분기하고, 서버가 주는
`requestId`는 고객 문의나 로그 확인에 사용할 수 있으므로 화면에 노출하지
않아도 보관하는 것이 좋다.

## 8. FE 구현 체크리스트

- [ ] 추천 장소 API를 호출하고 `items` 길이만큼 카드 렌더링
- [ ] 새 카테고리 5개를 필터와 배지에 추가
- [ ] `LEISURE_ACTIVITY`를 홈 메뉴에 추가하지 않음
- [ ] `placeId`와 `storyId`를 서로 혼동하지 않음
- [ ] 저장 상태는 `saved` 또는 `savedByMe`로 표시
- [ ] 비회원 저장 시 로그인 성공 뒤 원래 ID로 한 번만 재시도
- [ ] 이미지·목록 빈 상태와 503 재시도 상태를 분리
- [ ] cursor를 임의로 만들거나 수정하지 않음

## 9. FE 이슈 완료 조건

다음 조건을 모두 확인하면 FE 이슈를 완료할 수 있다.

- 홈에서 목데이터 대신 `curated-courses` 응답이 표시된다.
- 카테고리 선택 시 서버에 `category`를 전달하고 결과 카드가 해당
  카테고리로만 구성된다.
- 새 카테고리의 사용자용 이름이 코드값 그대로 노출되지 않는다.
- 카드 클릭이 `placeId` 기준의 장소 상세 또는 지도 화면으로 이동한다.
- 로그인 회원의 저장·해제가 카드와 저장 목록에서 일관되게 보인다.
- `items: []`, `thumbnailUrl: null`, `hasMore: true`, `503`을 각각 확인했다.
- 브라우저 Network 탭에서 요청 URL, 쿠키, cursor, 오류 응답을 확인했다.

## 10. FE 이슈 작성 시 함께 적을 내용

**제목 예시**

```text
feat(home): 한옥·전통문화 카테고리 확장 API 연동
```

**관련 링크**

- BE 이슈: [YRootLab/OnMaru-backend#246](https://github.com/YRootLab/OnMaru-backend/issues/246)
- BE API 구현: PR #282
- BE canonical 경로 정리: PR #292
- 이 문서: `docs/toFE/home-category-guide.md`

**검증 시나리오**

1. 로그인하지 않은 상태에서 추천 장소와 인기 지역을 조회한다.
2. `HANOK_STAY`, `CULTURE_ART`, `TRADITIONAL_FOOD`,
   `GARDEN_ECOLOGY`, `LOCAL_SCENE` 필터를 각각 조회한다.
3. 카드의 장소 상세 이동과 저장·저장 취소를 확인한다.
4. 인기 오디오를 재생하고 `storyId`로 오디오를 저장·취소한다.
5. 빈 목록, 이미지 없음, cursor 만료, 503 화면을 확인한다.

FE 이슈에서는 이 문서의 API 계약을 임의로 재정의하지 않는다. 화면 문구,
아이콘, 카드 배치와 같은 표현은 FE가 결정하되, ID·category·cursor·오류
코드는 BE 계약을 그대로 사용한다.
