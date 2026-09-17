# FE 전달: 여정 탐색 기록과 LLM 장소 설명 데이터 계약

작성일: 2026-09-17  
관련 Issue: #228  
대상: OnMaru FE 구현 담당

이 문서는 FE 레이아웃을 지시하지 않는다. 홈, 여정 탐색 화면, 마이페이지에서 사용할 수 있는 **BE 제공 데이터와 상태 의미**만 정리한다. 화면 배치, 컴포넌트 구조, 노출 우선순위는 FE가 결정한다.

JSON key는 API 계약 안정성을 위해 영어를 사용한다. 대신 각 값이 사용자에게 어떤 의미로 보이면 좋은지, 어떤 문구 톤으로 다루면 좋은지를 한국어로 풀어 쓴다.

## 기존 한계와 이번 개선

이번 추가 기능은 단순히 API 필드를 늘리는 작업이 아니다. 기존 여정 탐색에서 사용자가 느낄 수 있던 아쉬움을 줄이고, FE가 사용자 상황에 맞는 화면을 만들 수 있도록 BE가 더 좋은 재료를 내려주는 작업이다.

| 기존 한계 | 사용자가 느낄 수 있는 문제 | 이번 개선 방향 |
|---|---|---|
| 탐색 질문과 결과가 한 번 쓰이고 사라짐 | “아까 뭐라고 물어봤지?”, “그때 추천 다시 보고 싶은데” 같은 흐름을 살리기 어렵다. | 최근 여정 탐색 기록 `JourneyThread`를 저장하고 다시 열 수 있게 한다. |
| 저장한 여정과 최근 탐색의 역할이 섞일 수 있음 | 저장한 결과인지, 그냥 최근에 보던 탐색인지 사용자가 헷갈릴 수 있다. | `JourneyThread`는 “이어보기”, `SavedJourney`는 “보관한 결과”로 책임을 나눈다. |
| 장소 카드가 이름/기본 정보 중심으로 보일 수 있음 | 왜 나에게 맞는 장소인지 납득하기 어렵고, LLM을 쓴 값어치가 덜 보인다. | 추천 이유, 잘 맞는 상황, 방문 팁, 후기 요약을 `enrichment`로 제공한다. |
| 모든 사용자에게 같은 정보 밀도를 보여줄 수 있음 | 가볍게 훑는 사용자에게는 과하고, 실제 계획 중인 사용자에게는 부족할 수 있다. | `presentationHints`로 설명 깊이와 우선 노출 섹션을 고를 수 있게 한다. |
| 근거가 부족한 설명도 그럴듯하게 보일 위험이 있음 | 사용자가 과장된 정보로 오해할 수 있다. | `coverageStatus`, `unknowns`, `evidenceRefs`로 근거 수준과 한계를 함께 내려준다. |

## FE와 먼저 맞추고 싶은 질문

아래 질문은 BE가 화면을 정하겠다는 뜻이 아니다. FE가 “어느 사용자에게 어떤 답변을 얼마나 자세히 보여줄지” 판단할 수 있도록, BE가 추가로 어떤 힌트 데이터를 내려주면 좋은지 함께 정하기 위한 협의 항목이다.

| 질문 | 왜 필요한가 | BE가 추가 제공할 수 있는 값 |
|---|---|---|
| 사용자가 지금 가볍게 훑는 중인가, 실제 여행을 계획 중인가? | 가볍게 보는 사용자에게 긴 설명을 먼저 보여주면 부담스럽고, 계획 중인 사용자에게는 방문 팁과 주의점이 더 중요하다. | `intentType`: `BROWSE`, `PLAN`, `COMPARE`, `RESUME` |
| 사용자가 한옥 자체에 관심이 큰가, 지역 여행 코스가 더 중요한가? | 한옥 관심 사용자에게는 건축/분위기 설명이, 코스 관심 사용자에게는 동선과 주변 장소 설명이 더 잘 맞는다. | `interestFocus`: `HANOK`, `REGION`, `FOOD`, `PHOTO`, `QUIET`, `FAMILY`, `COUPLE` |
| 사용자가 빠른 추천을 원하는가, 이유를 납득하고 싶은가? | 빠른 추천이면 태그와 짧은 이유가 먼저, 납득형이면 근거와 후기 요약을 더 잘 보여줘야 한다. | `answerDepth`: `SHORT`, `BALANCED`, `DETAILED` |
| 사용자가 처음 온 사람인가, 이전 탐색을 이어오는 사람인가? | 처음이면 설명을 친절히 풀고, 이어오는 사용자는 지난 질문과 바뀐 조건을 먼저 보여주는 편이 자연스럽다. | `journeyContext`: `FIRST_VISIT`, `RETURNING_THREAD`, `SAVED_RESUME` |
| 사용자가 지금 비교 중인가, 하나를 고르려는 중인가? | 비교 중이면 카드 간 차이점이 중요하고, 고르는 단계면 “왜 이게 제일 맞는지”가 중요하다. | `decisionStage`: `DISCOVERING`, `COMPARING`, `CHOOSING`, `SAVED` |
| 후기 요약을 어느 정도까지 보여줘도 좋은가? | 후기는 신뢰를 만들지만 과하면 화면이 무거워진다. FE가 카드 크기와 노출 깊이를 정해야 한다. | `reviewCoverage`, `reviewSummary.positive`, `reviewSummary.cautions` |
| 근거 보기를 화면에 둘 것인가? | 기본 카드에는 숨기되, 상세나 펼침 영역에서는 “왜 이렇게 말했는지”를 보여줄 수 있다. | `evidenceRefs`, `coverageStatus`, `unknowns` |
| 설명이 부족한 장소를 어떻게 다룰 것인가? | 근거가 부족한데 억지로 풍성하게 보이면 신뢰가 떨어진다. 부족함을 화면에서 자연스럽게 표현해야 한다. | `coverageStatus=INSUFFICIENT_EVIDENCE`, `unknowns` |

## 추가로 내려주면 좋은 사용자 맞춤 힌트

FE가 원하면 BE는 아래 값을 enrichment 옆에 붙여 줄 수 있다. 이 값들은 화면 배치를 강제하지 않고, “이 사용자에게 어떤 설명을 먼저 보여줄지” 고르는 데 쓰는 보조 신호다.

```json
{
  "presentationHints": {
    "intentType": "PLAN",
    "interestFocus": ["HANOK", "QUIET"],
    "answerDepth": "BALANCED",
    "journeyContext": "RETURNING_THREAD",
    "decisionStage": "COMPARING",
    "primaryMessage": "조용한 한옥 산책을 원해서 시간대 팁과 골목 분위기를 함께 봤어요.",
    "secondaryMessage": "사진보다 여유로운 동선을 더 중시하는 추천입니다.",
    "recommendedSections": ["whyRecommended", "bestFor", "visitTips", "reviewSummary"]
  }
}
```

필드 의미:

| 필드 | FE 의미 |
|---|---|
| `intentType` | 사용자가 지금 탐색, 계획, 비교, 이어보기 중 어느 흐름에 가까운지 나타낸다. |
| `interestFocus` | 사용자가 특히 관심 있어 보이는 축이다. 카드 태그나 첫 설명 우선순위에 쓸 수 있다. |
| `answerDepth` | 설명을 짧게, 균형 있게, 자세히 중 어디에 맞추면 좋은지 알려준다. |
| `journeyContext` | 처음 들어온 탐색인지, 이전 대화를 이어오는지, 저장한 여정을 다시 여는지 구분한다. |
| `decisionStage` | 사용자가 후보를 넓히는 단계인지, 비교하는 단계인지, 선택에 가까운지 나타낸다. |
| `primaryMessage` | 카드나 결과 상단에 한 줄로 보여줄 수 있는 핵심 설명이다. |
| `secondaryMessage` | 핵심 설명을 보조하는 문장이다. 공간이 부족하면 숨겨도 된다. |
| `recommendedSections` | FE가 펼침/접힘이나 카드 우선순위를 정할 때 참고할 설명 섹션 목록이다. |

## 제공 데이터 요약

사용자는 여정 탐색에서 LLM 비용이 들어간 질문과 결과를 한 번 보고 끝내지 않는다. “지난번에 내가 뭐라고 물어봤더라?”, “그때 추천받은 한옥 코스 다시 보고 싶은데?”, “왜 이 장소가 나한테 맞는 거지?” 같은 흐름이 자연스럽다.

따라서 BE는 두 종류의 데이터를 추가로 제공할 예정이다.

1. 최근 여정 탐색 기록 `JourneyThread`
2. 근거 기반 장소/한옥 설명 `LLM enrichment`

## 용어를 화면 말로 풀어보기

| API 용어 | FE/사용자 관점 의미 | 화면 문구 후보 |
|---|---|---|
| `JourneyThread` | 사용자가 여정 탐색에서 주고받은 질문 흐름 | 최근 탐색, 이어보기, 탐색 기록 |
| `turn` | 사용자가 한 번 입력한 질문과 그에 대한 결과 단위 | 질문, 요청, 다시 찾아본 내용 |
| `exploration` | 현재 복구 가능한 여정 탐색판 | 여정 탐색 결과, 추천판 |
| `SavedJourney` | 사용자가 명시적으로 저장한 최종 여정 | 저장한 여정, 보관한 여정 |
| `enrichment` | 장소/한옥 카드에 덧붙이는 설명 묶음 | 추천 이유, 이런 분께 좋아요, 방문 팁 |
| `evidenceRefs` | BE/AI가 설명을 만들 때 참고한 내부 근거 ID | 근거 보기용 연결값. 기본 노출은 선택 |
| `unknowns` | 확실히 말할 수 없어서 사용자에게 알려야 하는 제한 | 확인이 필요한 점, 아직 알 수 없는 정보 |

## 화면별 데이터 경계

| 화면 후보 | FE가 사용할 수 있는 데이터 | BE가 강제하지 않는 것 |
|---|---|---|
| 홈 또는 여정 탐색 | 최근 탐색 기록 목록, 이어가기용 `explorationId`, 최신 진행 상태, 저장 여부 | 좌측/우측/하단 배치 |
| 여정 결과 화면 | 장소별 설명 묶음, 추천 이유, 잘 맞는 상황, 방문 팁, 태그 | 카드 내부 디자인 |
| 마이페이지 | 저장한 여정, 월간 timeline, 선택적으로 최근 thread | 마이페이지 탭 구조 |

권장 UX 구분:

- 최근 탐색 기록: 사용자가 이전에 질문했던 흐름을 빠르게 다시 여는 영역
- 저장한 여정: 사용자가 마음에 들어 따로 보관한 결과
- 월별 활동 기록: 나의 활동을 시간순으로 모아 보는 영역

## 최근 여정 탐색 기록 목록

후보 API:

```http
GET /api/v1/me/journey-threads?limit=20&cursor=...
```

응답 예시:

```json
{
  "schemaVersion": "1.2",
  "items": [
    {
      "threadId": "91a0d1da-5d55-4a80-8fd2-8d8ef4fb1799",
      "explorationId": "1c178047-07ec-4b4a-9a79-3901c53b0e5c",
      "title": "전주에서 조용한 한옥 산책",
      "lastUserQueryPreview": "전주에서 조용한 한옥 여행을 찾고 싶어요",
      "lastOutcome": "INITIAL_BOARD",
      "latestRunStatus": "COMPLETED",
      "savedJourneyId": null,
      "candidateCount": 3,
      "pinnedCount": 1,
      "updatedAt": "2026-09-17T01:30:00Z"
    }
  ],
  "nextCursor": null,
  "hasMore": false
}
```

### 필드 의미

| 필드 | FE 의미 |
|---|---|
| `threadId` | 최근 탐색 기록 자체의 ID. 기록 삭제나 상세 조회에 사용한다. |
| `explorationId` | 실제 여정 탐색판을 다시 불러올 ID. 사용자가 “이어보기”를 누르면 이 값으로 snapshot을 복구한다. |
| `title` | BE가 만든 짧은 제목. 없거나 품질이 낮으면 FE가 `lastUserQueryPreview`를 보조 문구로 써도 된다. |
| `lastUserQueryPreview` | 사용자 입력을 표시용으로 줄이고 민감정보를 가린 문장. 원문 전체가 아니다. |
| `lastOutcome` | 마지막 결과의 성격. 예: 처음 후보를 보여줬는지, 추가 제안을 했는지, 질문을 더 요청했는지. |
| `latestRunStatus` | 지금도 추천 생성이 진행 중인지, 완료됐는지, 실패했는지 판단하는 상태. |
| `savedJourneyId` | 이 탐색 결과가 저장한 여정과 연결돼 있으면 채워진다. |
| `candidateCount` | 당시 후보로 잡힌 장소/한옥 개수. 작은 보조 정보로 쓸 수 있다. |
| `pinnedCount` | 사용자가 찜하거나 고정한 후보 수. 사용자의 선택 흔적을 보여줄 때 쓸 수 있다. |
| `updatedAt` | 이 탐색이 마지막으로 움직인 시각. “방금 전”, “어제” 같은 상대 시간 표시에 적합하다. |

### FE 처리 규칙

- `explorationId`가 있으면 기존 `GET /explorations/{id}`로 snapshot을 복구한다.
- `savedJourneyId`가 있으면 저장한 여정 상세로도 이동할 수 있다.
- `lastUserQueryPreview`는 이미 민감정보가 가려진 표시용 문장이다.
- `latestRunStatus=RUNNING|QUEUED`면 이어가기 시 “아직 추천을 정리하는 중” 상태를 먼저 보여준다.
- `hasMore=false`면 다음 페이지를 요청하지 않는다.
- 개인 API이므로 로그아웃/탈퇴 후 브라우저에 남아 있는 개인 화면 캐시를 지운다.

## 탐색 기록 상세

후보 API:

```http
GET /api/v1/me/journey-threads/{threadId}
```

후보 응답:

```json
{
  "schemaVersion": "1.2",
  "threadId": "91a0d1da-5d55-4a80-8fd2-8d8ef4fb1799",
  "explorationId": "1c178047-07ec-4b4a-9a79-3901c53b0e5c",
  "title": "전주에서 조용한 한옥 산책",
  "turns": [
    {
      "turnId": "3e0af703-9300-4d15-8e3a-3c4a045bcff0",
      "queryPreview": "시장은 빼고 조용한 한옥 중심으로 다시 보여줘",
      "outcome": "PROPOSAL",
      "createdAt": "2026-09-17T01:35:00Z"
    }
  ],
  "snapshotUrl": "/api/v1/explorations/1c178047-07ec-4b4a-9a79-3901c53b0e5c"
}
```

FE는 상세 화면 여부를 자유롭게 결정할 수 있다. 단, board truth는 `snapshotUrl`의 exploration snapshot이다.

FE가 별도 상세 화면을 만들지 않아도 된다. 예를 들어 홈에서 최근 탐색 목록만 보여주고, 클릭 시 바로 기존 여정 탐색 화면으로 보내도 된다.

## 장소/한옥 설명 묶음

`enrichment`는 장소 카드에 붙일 수 있는 설명 묶음이다. “왜 추천됐는지”, “누구에게 잘 맞는지”, “언제 가면 좋은지”, “후기에서 자주 나온 말은 무엇인지”를 보여주기 위한 데이터다.

모든 필드가 항상 있지는 않다. BE/AI가 근거를 충분히 찾지 못하면 일부 필드는 비어 있거나 `coverageStatus`가 낮게 내려간다.

```json
{
  "schemaVersion": "1.2",
  "resourceRef": {"type": "PLACE", "id": "p-jeonju-hanok-village"},
  "coverageStatus": "SUPPORTED",
  "summary": "전주 한옥마을은 한옥 거리와 전통 문화 체험을 한 번에 살펴보기 좋은 장소입니다.",
  "whyRecommended": [
    {
      "text": "사용자가 요청한 조용한 한옥 산책 조건과 지역 조건을 모두 만족합니다.",
      "evidenceRefs": ["catalog:p-jeonju-hanok-village"]
    }
  ],
  "bestFor": [
    {"label": "한옥 입문", "reason": "대표적인 한옥 거리와 문화 공간이 밀집해 있습니다."}
  ],
  "visitTips": [
    {
      "label": "방문 팁",
      "text": "사진 촬영 중심이면 낮 시간, 조용한 산책이면 이른 오전을 우선 고려하세요.",
      "evidenceRefs": ["review:cluster:time"]
    }
  ],
  "hanokHighlights": [
    {
      "label": "기와와 골목",
      "description": "한옥 지붕선과 골목 밀도를 함께 볼 수 있습니다.",
      "evidenceRefs": ["catalog:p-jeonju-hanok-village"]
    }
  ],
  "reviewSummary": {
    "coverage": "PARTIAL",
    "reviewCount": 12,
    "positive": ["거리 분위기와 사진 포인트가 자주 언급됩니다."],
    "cautions": ["혼잡 시간대에는 조용한 산책 경험이 약해질 수 있습니다."],
    "evidenceRefs": ["review:cluster:atmosphere"]
  },
  "presentationHints": {
    "intentType": "PLAN",
    "interestFocus": ["HANOK", "QUIET"],
    "answerDepth": "BALANCED",
    "journeyContext": "RETURNING_THREAD",
    "decisionStage": "COMPARING",
    "primaryMessage": "조용한 한옥 산책을 원해서 골목 분위기와 방문 시간대를 함께 봤어요.",
    "secondaryMessage": "사진 명소보다 여유로운 동선을 더 중시한 추천입니다.",
    "recommendedSections": ["whyRecommended", "bestFor", "visitTips", "reviewSummary"]
  },
  "tags": ["한옥산책", "전통문화", "사진명소"],
  "unknowns": ["실시간 혼잡도는 제공되지 않습니다."]
}
```

### FE 렌더링 규칙

- `coverageStatus=SUPPORTED|PARTIAL`일 때만 설명 묶음을 적극 표시한다.
- `INSUFFICIENT_EVIDENCE`면 “아직 충분히 확인된 설명이 없어요” 정도의 빈 상태로 처리한다.
- `UNAVAILABLE`이면 과거 설명을 재사용하지 않는다. 삭제/비공개/권리 문제로 더 이상 보여주면 안 되는 상태일 수 있다.
- `unknowns`는 사용자가 오해할 수 있는 빈 영역을 설명하는 데 사용한다. 예: “실시간 혼잡도는 제공되지 않아요.”
- `tags`에는 `#`이 포함되지 않는다. FE가 필요하면 시각적으로만 `#`을 붙인다.
- `evidenceRefs`는 FE가 반드시 노출할 필요는 없지만, “근거 보기” UI가 있으면 연결할 수 있다.

### 설명 필드별 화면 의도

| 필드 | 사용자에게 주는 느낌 | 화면 문구 방향 |
|---|---|---|
| `summary` | 이 장소가 어떤 곳인지 한 문장으로 잡아준다. | 카드 설명 첫 줄 |
| `whyRecommended` | 사용자의 질문과 이 장소가 맞닿는 이유를 설명한다. | “왜 추천했나요?” |
| `bestFor` | 어떤 취향/상황의 사용자에게 좋은지 알려준다. | “이런 분께 좋아요” |
| `visitTips` | 실제 방문 전에 알면 좋은 팁을 준다. | “방문 팁” |
| `hanokHighlights` | 한옥·전통 공간으로서 볼 만한 포인트를 짚는다. | “한옥 포인트” |
| `reviewSummary` | 후기에서 반복적으로 드러난 분위기와 주의점을 요약한다. | “후기에서 많이 나온 말” |
| `presentationHints` | 이 사용자에게 어떤 설명을 먼저 보여주면 좋을지 알려주는 보조 신호다. | 카드 우선순위, 접힘/펼침 기준 |
| `tags` | 카드를 빠르게 훑을 수 있게 하는 짧은 키워드다. | 칩/태그 |
| `unknowns` | BE가 확실히 말할 수 없는 부분을 숨기지 않고 알려준다. | “확인 필요” |

## coverageStatus

| 값 | FE 의미 |
|---|---|
| SUPPORTED | 충분한 근거가 있어 설명을 자연스럽게 보여줄 수 있음 |
| PARTIAL | 일부 정보만 있음. “대체로”, “참고로”처럼 조심스러운 톤이 어울림 |
| INSUFFICIENT_EVIDENCE | 설명을 만들 근거가 부족함. 억지 설명을 붙이지 않는 상태 |
| UNAVAILABLE | 비공개/삭제/권리 문제로 사용 불가. 캐시된 옛 설명도 노출하지 않음 |

## 정책: LLM이 사용할 수 있는 데이터

허용 source:

- 공식/내부 catalog의 장소 기본 정보
- 한옥 상세 정보와 `contentTags`
- Odii 음성/해설 transcript와 승인된 장소 연결 정보
- moderation을 통과한 방문 후기 묶음
- 공개 가능한 지역 인사이트 요약
- 특정 revision으로 고정된 내부 지식 묶음
- 사용자의 현재 질문과 고정/제외 같은 action state

금지:

- 출처 없는 역사/문화 사실 생성
- 숨김/삭제/신고 후기 사용
- 후기 수 부족한데 “대부분 좋다”처럼 단정
- 실시간 혼잡도, 운영시간, 가격을 근거 없이 추정
- provider raw ID, token, cookie, 내부 운영 note 노출
- 개인 식별 정보 또는 민감 속성 노출

## 저장·개인정보 정책

- 회원의 탐색 기록은 계정 유지 기간 동안 개인 데이터로 저장한다.
- 탈퇴 시 탐색 기록, 질문 단위, 설명 묶음, 저장한 여정은 삭제 또는 tombstone 처리된다.
- 안전/개인정보 정책상 즉시 거절된 질문은 저장하지 않는다.
- 일부 민감정보가 포함된 질문은 가려진 preview만 FE에 제공한다.
- 운영 로그와 LLM 비용 집계 데이터는 사용자에게 보여주는 데이터와 분리한다.

## SavedJourney와의 차이

| 항목 | JourneyThread | SavedJourney |
|---|---|---|
| 목적 | 최근 탐색 이어보기 | 명시적으로 저장한 결과 보관 |
| 생성 | 사용자가 여정 탐색에서 질문을 남길 때 자동 | 사용자가 저장 버튼 클릭 |
| 내용 | 가려진 질문 미리보기, 최신 상태, 탐색판 연결값 | 정리된 여정 결과 snapshot |
| 위치 후보 | 홈/탐색, 선택적으로 마이페이지 | 마이페이지, 저장 목록 |
| 삭제 | 최근 탐색 기록 삭제 | 저장한 여정 삭제 |

## FE가 준비하면 좋은 상태

- 최근 탐색 기록을 불러오는 중
- 최근 탐색 기록이 없음
- 최근 탐색 기록을 불러오지 못함
- 추천 생성이 아직 진행 중인 탐색 기록
- 추천 생성이 완료된 탐색 기록
- 저장한 여정과 연결된 탐색 기록
- 삭제됐거나 더 이상 열 수 없는 탐색 기록
- 장소 설명을 충분히 보여줄 수 있음
- 장소 설명이 일부만 가능함
- 장소 설명 근거가 부족함
- 장소 설명을 보여줄 수 없음
- 민감정보가 가려진 질문 미리보기
- 로그아웃 후 개인 화면 캐시 비우기

## 후속 구현 단위

1. 최근 Journey thread 목록/상세/delete API
2. Enrichment JSON schema와 prompt output validator
3. 장소/한옥 enrichment 생성 pipeline
4. 사용자 맞춤 `presentationHints` 산출 규칙
5. FE fixture: thread list, enrichment supported/partial/insufficient
6. #127 E2E gate에 thread restore와 enrichment drift 추가
