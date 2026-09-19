# 스크린 속 한옥 (K-콘텐츠 연계) API 명세서

> **대상 화면**: 한옥 아카이브 페이지 (`/hanok`) 내 **`KCultureThemeFeed`** 컴포넌트  
> **관련 백엔드 요구사항**: `BE-REQ-010` / `ADR-0010`  
> **기본 엔드포인트**: `GET /api/v1/hanoks/screen-hanok`

---

## 1. 개요

K-드라마, 영화, K-POP 뮤직비디오/화보 촬영지로 등장한 한옥 장소 목록을 제공하는 API입니다.  
AI(Gemini + Google Search Grounding)를 통해 공공데이터 한옥 카탈로그와 실제 촬영지 보도 기사/웹 문서를 교차 검증하여, **실제 출처(URL)가 확인된 항목만** 안전하게 선별하여 제공합니다.

---

## 2. API 엔드포인트

```http
GET /api/v1/hanoks/screen-hanok
```

### 2.1 요청 쿼리 파라미터 (Query Parameters)

모든 파라미터는 선택 사항(Optional)입니다.

| 파라미터명 | 타입 | 필수 여부 | 기본값 | 설명 | 예시 |
| :--- | :--- | :---: | :--- | :--- | :--- |
| `region` | `string` | 선택 | `null` (전체) | 특정 지역(시·도 등)으로 필터링 | `충남`, `경북`, `전남`, `서울` |
| `mediaType` | `string` | 선택 | `null` (전체) | 미디어 매체 분류 필터 | `K_DRAMA`, `CINEMA`, `KPOP` |

> **인증 및 세션 (`Cookie`)**:
> - 로그인한 회원의 경우 브라우저 세션 쿠키(`__Host-onmaru-session`)가 함께 전송되면 각 장소의 `savedByMe`(찜 여부)가 `true`/`false`로 계산되어 내려옵니다.
> - 비로그인(게스트) 사용자는 모든 항목이 `savedByMe: false`로 반환됩니다.

---

## 3. 매체 분류 (MediaType) 및 UI 뱃지 매핑

백엔드 응답의 `categoryLabel`과 `categoryIcon` 필드에 이미 표시용 텍스트와 이모지가 가공되어 내려오므로, 프론트엔드에서는 추가 매핑 로직 없이 바로 렌더링할 수 있습니다.

| `mediaType` 코드 | `categoryLabel` (화면 표시 라벨) | `categoryIcon` (이모지) | 대상 콘텐츠 예시 |
| :--- | :--- | :---: | :--- |
| **`K_DRAMA`** | `K-드라마 · 사극 로케이션` | 🎬 | 사극, 시대극, 현대 드라마 촬영지 |
| **`CINEMA`** | `한국 영화 로케이션` | 🎥 | 영화 속 주요 배경 및 고택 촬영지 |
| **`KPOP`** | `K-POP 뮤비 · 화보 로케이션` | 🎵 | 뮤직비디오, 앨범 콘셉트 포토 촬영지 |

---

## 4. 응답 데이터 명세 (Response Body)

### 4.1 응답 구조 (JSON Schema)

- **HTTP Status**: `200 OK`
- **Cache-Control**: `no-store`

```json
{
  "total": 2,
  "items": [
    {
      "placeId": "p-001",
      "name": "서천 이하복 고택",
      "region": "충남",
      "imageUrl": "https://tong.visitkorea.or.kr/cms/resource/12/3456789_image2_1.jpg",
      "mediaType": "K_DRAMA",
      "categoryLabel": "K-드라마 · 사극 로케이션",
      "categoryIcon": "🎬",
      "workTitle": "미스터 션샤인",
      "subtitle": "주인공 애신의 본가로 등장하여 전통 고택의 단아한 멋을 보여준 장소",
      "tags": ["#사극로케이션", "#드라마촬영지", "#미스터션샤인"],
      "sourceUrl": "https://www.yna.co.kr/view/AKR20180901000100000",
      "sourceTitle": "[드라마 로케이션] 미스터 션샤인 속 그 고택, 서천 이하복 가옥",
      "savedByMe": false
    },
    {
      "placeId": "p-002",
      "name": "안동 하회마을 양진당",
      "region": "경북",
      "imageUrl": "https://tong.visitkorea.or.kr/cms/resource/98/7654321_image2_1.jpg",
      "mediaType": "CINEMA",
      "categoryLabel": "한국 영화 로케이션",
      "categoryIcon": "🎥",
      "workTitle": "관상",
      "subtitle": "조선 시대 고유의 풍광과 대가옥의 위엄을 담아낸 영화 주요 촬영지",
      "tags": ["#영화촬영지", "#안동하회마을", "#관상"],
      "sourceUrl": "https://www.chosun.com/culture-life/2013/09/15/example",
      "sourceTitle": "영화 '관상'의 숨은 주역, 안동 고택 로케이션 탐방",
      "savedByMe": true
    }
  ]
}
```

### 4.2 필드별 상세 설명 (Data Dictionary)

| 필드명 | 타입 | Nullable | 설명 및 활용 가이드 |
| :--- | :--- | :---: | :--- |
| **`total`** | `number` | N | 현재 반환된 총 아이템 개수 (`items.length`) |
| **`placeId`** | `string` | N | 온마루 통합 표준 장소 식별자 (Canonical Place ID).<br>👉 **상세 조회**(`GET /api/v1/hanoks/{placeId}`) 및 **찜 토글**(`PUT/DELETE /api/v1/saved-resources/places/{placeId}`)에 동일하게 사용됩니다. |
| **`name`** | `string` | N | 한옥 장소 공식 명칭 (예: `"서천 이하복 고택"`) |
| **`region`** | `string` | N | 장소가 위치한 대표 행정구역 (예: `"충남"`, `"경북"`, `"서울"`) |
| **`imageUrl`** | `string` | Y | 대표 썸네일 이미지 URL (이미지가 없을 경우 `null`일 수 있으므로 FE Fallback 이미지 처리 권장) |
| **`mediaType`** | `string` | N | 매체 구분 코드 (`K_DRAMA` \| `CINEMA` \| `KPOP`) |
| **`categoryLabel`** | `string` | N | 카드 상단 뱃지에 노출할 한글 매체 구분명 |
| **`categoryIcon`** | `string` | N | 뱃지 앞단 또는 카드에 표시할 이모지 (`🎬`, `🎥`, `🎵`) |
| **`workTitle`** | `string` | N | **등장한 작품명 / 콘텐츠명** (예: `"미스터 션샤인"`, `"방탄소년단 화보"`) |
| **`subtitle`** | `string` | N | **작품 속 등장 맥락 및 한 줄 소개 문구** |
| **`tags`** | `string[]` | N | 해시태그 목록 (배열 형태, 예: `["#사극로케이션", "#드라마촬영지"]`) |
| **`sourceUrl`** | `string` | N | **촬영지 근거가 되는 보도 기사/웹페이지 원문 링크** (아웃링크 이동에 사용) |
| **`sourceTitle`** | `string` | N | **근거 출처 문서/기사의 헤드라인 제목** |
| **`savedByMe`** | `boolean` | N | 현재 사용자의 해당 장소 **찜(북마크) 여부** (`true`/`false`) |

---

## 5. 프론트엔드 연동 & 인터랙션 가이드

### 5.1 카드 렌더링 추천 레이아웃 (`KCultureThemeFeed`)
```
+-------------------------------------------------------------+
| [🎬 K-드라마 · 사극 로케이션]              [♥ 찜 버튼: savedByMe] |
|                                                             |
|   [ 썸네일 이미지 (imageUrl) ]                                |
|                                                             |
|   작품명: 미스터 션샤인 (workTitle)                          |
|   장소명: 서천 이하복 고택 (name) · 충남 (region)             |
|   설명: 주인공 애신의 본가로 등장하여... (subtitle)            |
|                                                             |
|   #사극로케이션 #드라마촬영지 (tags)                           |
|                                                             |
|   🔗 출처: [드라마 로케이션] 미스터 션샤인 속 그 고택... (sourceTitle) |
+-------------------------------------------------------------+
```

### 5.2 장소 상세 및 찜(Saved) 상호작용
1. **장소 상세 모달/페이지 열기**:
   - 카드를 클릭했을 때 `placeId`를 사용하여 기존 한옥 상세 모달/페이지(`GET /api/v1/hanoks/{placeId}`)를 오픈합니다.
2. **하트(찜) 버튼 클릭 시**:
   - 찜 등록: `PUT /api/v1/saved-resources/places/{placeId}` (로그인 필수)
   - 찜 해제: `DELETE /api/v1/saved-resources/places/{placeId}` (로그인 필수)
   - 비로그인 상태에서 하트 클릭 시 로그인 유도 팝업 노출 권장.
3. **출처 링크(근거 보기)**:
   - `sourceUrl`과 `sourceTitle`을 통해 사용자가 실제 촬영지 관련 기사를 새 창(`target="_blank"`)으로 열람할 수 있도록 지원합니다.

---

## 6. 에러 응답 규격 (Error Contract)

오류 발생 시 OnMaru 표준 `ApiErrorResponse` 형식으로 응답합니다.

```json
{
  "schemaVersion": "1.2",
  "code": "INVALID_REQUEST",
  "message": "지원하지 않는 mediaType 값입니다.",
  "requestId": "req-sh-1234-abcd",
  "details": {}
}
```

| HTTP 상태 코드 | 에러 코드 (`code`) | 상황 설명 |
| :--- | :--- | :--- |
| **`400 Bad Request`** | `INVALID_REQUEST` | 잘못된 `mediaType` 파라미터 값을 전달한 경우 |
| **`503 Service Unavailable`** | `SERVICE_UNAVAILABLE` | 내부 카탈로그 데이터 조회가 일시적으로 불가능한 경우 |
