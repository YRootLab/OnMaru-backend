# Backend Requirement: 스크린 속 한옥 (K-콘텐츠) 서비스 (`BE-REQ-SCREEN-HANOK`)

> **Requirement Group**: `BE-REQ-SCREEN-HANOK`
> **Source Feature**: `HANOK-F005`
> **Target Consumer**: `/hanok` Page (`KCultureThemeFeed`)
> **Status**: Implemented (see [ADR-0010](../../decisions/0010-screen-hanok-ai-auto-publish-without-review.md))

---

## 1. 배경

FE는 `/hanok` 페이지의 K-컬처 섹션을 "스크린 속 한옥(드라마·영화·K-POP 뮤비 촬영지)" 테마로 요청했다. TourAPI를 비롯한 공공데이터에는 "이 장소가 어떤 작품에 등장했는지"를 나타내는 필드가 없어서, AI가 리서치해 새로 만들어야 하는 정보다. [ADR-0010](../../decisions/0010-screen-hanok-ai-auto-publish-without-review.md)에 따라, 이 정보는 **사람 검수 없이 매일 자동 게시**되며, 유일한 안전장치는 **출처(URL) 없는 매칭을 자동 제외**하는 것이다.

## 2. 후보 장소 범위

`HanokListStore.findPublishedSnapshot()`가 반환하는, **이미 공개(PUBLIC) 상태로 게시된 한옥/전통 요소 장소 전체**를 후보로 사용한다. 새 TourAPI `cat1/cat2/cat3` 카테고리 코드를 추가하지 않는다 — 이미 검증되어 게시된 장소만 리서치 대상으로 삼아 블라스트 반경을 최소화한다.

## 3. 요구사항 인벤토리

| Requirement ID | Capability | HTTP Method & Endpoint | Read/Write |
| :--- | :--- | :--- | :---: |
| **`BE-REQ-010`** | 스크린 속 한옥 목록 조회 (지역/매체 필터) | `GET /api/v1/hanoks/screen-hanok` | Read |

### 3.1 요청 파라미터
- `region`: string (optional, 예: "충남")
- `mediaType`: string (optional, `K_DRAMA` \| `CINEMA` \| `KPOP`)

### 3.2 응답 바디 (200 OK)

```json
{
  "total": 1,
  "items": [
    {
      "placeId": "p-001",
      "name": "서천 이하복 고택",
      "region": "충남",
      "imageUrl": "https://tong.visitkorea.or.kr/cms/resource/...jpg",
      "mediaType": "K_DRAMA",
      "categoryLabel": "K-드라마 · 사극 로케이션",
      "categoryIcon": "🎬",
      "workTitle": "실제 작품명",
      "subtitle": "AI가 근거와 함께 요약한 한 줄 설명",
      "tags": ["#사극로케이션"],
      "sourceUrl": "https://example.com/news/001",
      "sourceTitle": "촬영지 보도 기사 제목",
      "savedByMe": false
    }
  ]
}
```

이미지는 새로 스크래핑하지 않고 기존 한옥 카탈로그의 `thumbnailUrl`을 그대로 재사용한다. 좌표(lat/lng)·주소는 기존 `HanokListProjection`에 없어 이번 응답에도 포함하지 않는다 — 필요하면 `GET /api/v1/hanoks/{placeId}` 상세 API를 별도 호출한다.

## 4. 배치 파이프라인 (일 1회)

`ScreenHanokSyncSchedulingAdapter`가 매일 03:00(KST)에 `ScreenHanokIngestionService.sync()`를 실행한다.

1. `HanokListStore.findPublishedSnapshot()`으로 후보 조회 (20건씩 배치).
2. Spring이 FastAPI 내부 계약(`POST /internal/v1/screen-hanok/research`, scope `screen-hanok.research:write`)을 호출한다. Spring/Java는 Gemini 등 LLM SDK를 절대 직접 호출하지 않는다(ADR-0010 Implementation Constraints).
3. FastAPI는 Gemini + Google Search grounding으로 후보별 실제 작품 등장 여부를 리서치하고, **grounding이 실제로 반환한 URL과 일치하는 출처만** 매칭으로 인정한다.
4. Spring은 `sourceUrl`이 없는 매칭을 자동 제외하고, 나머지를 원자적으로 게시한다(`ScreenHanokPlacementStore.publish`).
5. 리서치 호출이 실패하거나 배치 도중 예외가 발생하면 **부분 게시 없이 마지막으로 검증된 스냅샷을 유지**한다.

## 5. 알려진 한계 (의도적으로 다음 단계로 미룸)

- `InMemoryScreenHanokPlacementStore`는 인메모리다. 재기동 시 초기화되며, 다음 배치가 돌기 전까지 빈 목록을 반환한다. 이는 `MonthlyHanokEdition`과 동일한 현재 저장소 관례이며, JDBC 영속화는 별도 후속 작업이다.
- FastAPI의 Gemini 연동은 `ONMARU_SCREEN_HANOK_RESEARCH_ENABLED=true` 환경변수로만 켜진다. 실제 `gemini.api-key`가 없으면 절대 활성화하지 말 것.
- Gemini의 구조화 출력(JSON schema)과 Google Search grounding을 동시에 요청하는 조합이 실제 Gemini API에서 안정적으로 지원되는지는 실제 API 키로 아직 검증되지 않았다 — 프로덕션 활성화 전 별도 스모크 테스트가 필요하다.

## 6. 재검토

사용자/사내 오정보 제보가 누적되면 사람 검수 게이트 도입을 재검토한다 (ADR-0010 재검토 조건).
