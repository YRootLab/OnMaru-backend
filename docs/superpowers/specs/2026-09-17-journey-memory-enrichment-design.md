# Journey 탐색 기억과 LLM enrichment 계약 설계

작성일: 2026-09-17  
관련 Issue: #228  
상태: 설계 초안

## 배경

#124 SavedJourney는 사용자가 명시적으로 저장한 확정 board snapshot을 장기 보관한다. 하지만 Journey 탐색 경험에는 별도의 기억 계층이 더 필요하다. 사용자는 LLM 비용이 들어간 질문과 응답 흐름을 나중에 이어 보고 싶고, 서비스는 사용자가 어떤 맥락으로 장소를 고르고 있었는지 홈/탐색 화면에서 다시 연결해 줄 수 있어야 한다.

동시에 LLM을 쓰는 이상, 단순 장소 목록보다 더 풍성한 설명을 제공할 수 있다. 다만 OnMaru의 신뢰성은 source-grounded 답변에 달려 있으므로, LLM enrichment는 검증된 source와 evidence를 기반으로만 생성한다.

## 목표

- Journey 탐색 thread를 member private data로 장기 저장하는 정책과 API 후보를 정의한다.
- 홈/여정 탐색 화면이 최근 탐색 thread를 보여줄 수 있는 read model을 정의한다.
- 마이페이지가 저장한 여정과 최근 탐색 기록을 혼동하지 않도록 데이터 경계를 정의한다.
- 장소/한옥 카드에 붙일 LLM enrichment DTO와 source-grounding 정책을 정의한다.
- FE가 레이아웃을 자율 결정할 수 있도록 필드 의미, 상태, fallback 규칙만 전달한다.

## 비목표

- FE 레이아웃, 컴포넌트 배치, 카드 디자인을 확정하지 않는다.
- 실제 LLM prompt 구현이나 model provider 호출 코드를 작성하지 않는다.
- #127의 Spring/FastAPI/Postgres E2E gate를 대체하지 않는다.
- 공개 공유 링크, 다른 회원의 탐색 기록 조회, analytics dashboard는 포함하지 않는다.

## 용어

| 용어 | 의미 |
|---|---|
| Exploration | 하나의 Journey 탐색 workspace. 현재 run, board, actions, turns를 포함한다. |
| Thread | 사용자가 다시 열 수 있는 탐색 기억 단위. 하나의 exploration을 대표한다. |
| Turn | 사용자의 질문 또는 수정 요청. query text와 createdAt을 가진다. |
| SavedJourney | 사용자가 명시적으로 저장한 immutable board snapshot. Thread와 다르다. |
| Enrichment | LLM이 source-grounded evidence를 바탕으로 생성한 장소/한옥 설명 block. |

## 제품 경계

### 홈/여정 탐색

홈 또는 `/discover`는 최근 탐색 thread를 보여주기에 가장 적합한 surface다. 이 영역은 사용자가 “지난번에 찾던 여정”으로 즉시 복귀하는 목적이다.

BE는 다음을 제공한다.

- 최근 thread 목록
- thread별 대표 제목, 마지막 사용자 질문, 마지막 응답 상태
- saved journey 연결 여부
- 이어가기 URL에 필요한 explorationId
- unavailable 또는 deleted 상태

FE는 이 데이터를 어디에 배치할지 결정한다. BE 계약은 “left section”, “sidebar” 같은 레이아웃 단어를 요구하지 않는다.

### 마이페이지

마이페이지는 보관·회고 surface다.

- 저장한 여정 목록과 상세는 SavedJourney API를 사용한다.
- 월간 활동 타임라인은 #126에서 saved place, Odii story, saved journey, review 중심으로 구현한다.
- 최근 thread는 마이페이지에도 표시할 수 있지만, 저장한 여정과 다른 item type으로 구분한다.

권장 구분:

| 사용자 인식 | BE 데이터 |
|---|---|
| 최근에 탐색한 것 | JourneyThread |
| 내가 저장한 여정 | SavedJourney |
| 이번 달 활동 | MemberTimeline item |

## 저장 정책

### 기본 정책

Journey thread는 member private data로 영구 저장한다. “영구”는 서비스 계정 유지 기간 동안 보관한다는 뜻이며, 탈퇴·삭제 요청·정책 위반에는 삭제된다.

| 데이터 | 보관 |
|---|---|
| member exploration metadata | 계정 유지 기간 |
| user turn query 원문 | 계정 유지 기간, 단 redaction 적용 |
| generated enrichment summary | 계정 유지 기간, source revision과 함께 |
| guest exploration | 기존 정책 유지. member 전환 전에는 장기 개인 기록이 아니다. |
| JSON 운영 로그 | 기존 7일 정책 유지. query 원문 제외 |
| LLM cost/audit ledger | 사용자 표시 데이터와 분리. query 원문 없이 run/cost/provider/status 중심 |

### 삭제 정책

- 회원 탈퇴 시 member thread, turns, generated enrichment, saved journeys를 삭제하거나 tombstone 처리한다.
- 개별 thread 삭제 API를 후속 구현한다.
- 삭제된 thread는 홈/탐색 목록, 마이페이지, timeline에 다시 노출하지 않는다.
- restore 또는 late AI callback이 삭제된 thread를 재생성하지 못하도록 terminal write 전에 member/exploration 상태를 재검증한다.

### redaction 정책

저장 전에 다음을 redaction 또는 reject한다.

- 전화번호, 이메일, 주민등록번호 형태
- 상세 주소와 방 번호 등 개인 위치 식별 정보
- token, cookie, API key로 보이는 값
- 의료·정치·종교 등 민감 속성 추론 요청
- 미성년자 등 안전 민감 정보

정책:

- safety/privacy hard reject query는 thread turn으로 저장하지 않는다.
- 부분 redaction 가능한 query는 redactedText와 redactionFlags를 저장한다.
- FE 표시용 text는 redactedText만 사용한다.
- 원문 보관이 꼭 필요한 경우라도 별도 암호화와 제한된 접근 권한 없이는 저장하지 않는다.

## 데이터 모델 후보

### journey_threads

| 필드 | 설명 |
|---|---|
| id | thread UUID |
| member_id | owner |
| exploration_id | linked exploration |
| title | 표시 제목. 서버 생성 또는 사용자 수정 가능 |
| last_user_query_preview | redacted preview |
| last_outcome | INITIAL_BOARD, PROPOSAL, CLARIFICATION_REQUIRED, NO_RESULTS, FAILED, CANCELLED |
| latest_run_status | QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED |
| saved_journey_id | optional |
| pinned_count | 현재 pinned refs 수 |
| candidate_count | 현재 board candidate 수 |
| updated_at | 목록 정렬 기준 |
| deleted_at | soft delete |

### journey_turn_memory

기존 `discovery.turns`를 확장하거나 별도 projection으로 둔다.

| 필드 | 설명 |
|---|---|
| id | turn UUID |
| thread_id | parent |
| exploration_id | exploration |
| actor_type | MEMBER |
| redacted_query | FE 표시 가능 text |
| redaction_flags | EMAIL, PHONE, ADDRESS 등 |
| created_at | 입력 시각 |
| run_id | 해당 turn이 만든 run |
| outcome | run 결과 |

### journey_enrichment_snapshots

| 필드 | 설명 |
|---|---|
| id | enrichment snapshot UUID |
| exploration_id | source exploration |
| state_version | board/action version |
| resource_ref | PLACE ref |
| source_manifest_hash | 사용한 source bundle hash |
| content | closed JSON enrichment |
| created_at | 생성 시각 |

## API 후보

### 최근 thread 목록

```http
GET /api/v1/me/journey-threads?limit=20&cursor=...
```

응답:

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

규칙:

- member private, `Cache-Control: no-store`.
- cursor는 memberId, limit, lastUpdatedAt, lastId, asOf, 10분 만료를 묶는다.
- 다른 member의 cursor는 404 또는 cursor invalid로 처리한다.
- deleted thread는 반환하지 않는다.

### thread 상세

```http
GET /api/v1/me/journey-threads/{threadId}
```

응답은 thread metadata, 최근 turn 목록, latest exploration snapshot URL을 포함한다. board 자체는 기존 `GET /explorations/{id}`를 재사용한다.

### thread 삭제

```http
DELETE /api/v1/me/journey-threads/{threadId}
```

soft delete 후 목록에서 제거한다. saved journey는 별도 명시 삭제 전까지 유지한다.

## Enrichment 계약

LLM enrichment는 board 또는 place card에 붙는 보조 설명이다. FE는 모든 field가 없을 수 있음을 가정한다.

```json
{
  "schemaVersion": "1.2",
  "resourceRef": {"type": "PLACE", "id": "p-jeonju-hanok-village"},
  "coverageStatus": "SUPPORTED",
  "summary": "전주 한옥마을은 한옥 거리와 전통 문화 체험을 한 번에 살펴보기 좋은 장소입니다.",
  "whyRecommended": [
    {
      "text": "사용자가 요청한 조용한 한옥 산책 조건과 지역 조건을 모두 만족합니다.",
      "evidenceRefs": ["catalog:p-jeonju-hanok-village", "review:cluster:quiet"]
    }
  ],
  "bestFor": [
    {"label": "한옥 입문", "reason": "대표적인 한옥 거리와 문화 공간이 밀집해 있습니다."},
    {"label": "도보 산책", "reason": "후보 장소 사이 이동 부담이 낮습니다."}
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
    "evidenceRefs": ["review:cluster:atmosphere", "review:cluster:crowd"]
  },
  "tags": ["한옥산책", "전통문화", "사진명소"],
  "unknowns": ["실시간 혼잡도는 제공되지 않습니다."]
}
```

### coverageStatus

| 값 | 의미 |
|---|---|
| SUPPORTED | source가 충분해 enrichment를 제공한다. |
| PARTIAL | 일부 source만 있어 제한된 설명만 제공한다. |
| INSUFFICIENT_EVIDENCE | 설명 생성에 충분한 근거가 없다. |
| UNAVAILABLE | resource가 비공개/삭제/권리 문제로 사용할 수 없다. |

## Source allowlist

LLM은 다음 source만 사용할 수 있다.

- catalog canonical place detail
- hanok detail and content tags
- Odii story transcript and approved place link
- visit review public projection and moderation-safe review clusters
- region insights public observation summary
- internal corpus with revision-pinned manifest
- user current query and action state

금지:

- 출처 없는 역사·문화 사실 생성
- provider raw ID를 사용자 응답에 노출
- 숨김/삭제/신고 처리된 후기 사용
- 후기 수가 적은데 일반화된 평판 단정
- 실시간 혼잡도 또는 영업 정보가 없는데 추정 표시
- 개인 식별 정보나 민감 속성 추론

## Prompt output 정책

- closed JSON schema만 허용한다.
- 각 natural language claim은 evidenceRefs를 가져야 한다.
- evidence가 부족하면 `unknowns` 또는 `INSUFFICIENT_EVIDENCE`를 사용한다.
- tag는 2~8개, 각 2~12자 권장, `#` 포함 금지.
- reviewSummary는 reviewCount와 coverage를 반드시 포함한다.
- prompt에는 raw secret, cookie, provider token, internal operator note를 넣지 않는다.

## FE 전달 경계

BE는 DTO와 상태 의미를 제공한다. FE는 다음을 결정한다.

- 최근 thread를 홈, 탐색 화면, 마이페이지 중 어디에 얼마만큼 노출할지
- 좌측/우측/하단 등 레이아웃
- 카드 안에서 enrichment block 순서
- label 문구와 시각 표현

BE가 보장할 것:

- private cache 경계
- owner scoped 404
- cursor 안정성
- deleted/unavailable resource 은닉
- evidenceRefs와 coverageStatus의 일관성

## 후속 구현 분해

1. Journey thread persistence/read model 구현
   - `modules/journey/thread`
   - `apps/spring-api/web/me/journeythread`
   - list/detail/delete

2. LLM enrichment schema와 validator 구현
   - FastAPI closed schema
   - Spring validator
   - evidence allowlist

3. Enrichment generation pipeline 구현
   - source bundle 구성
   - prompt template
   - source manifest hash
   - fallback to PARTIAL/INSUFFICIENT_EVIDENCE

4. FE 전달 계약 fixture 추가
   - thread list normal
   - thread deleted/unavailable
   - enrichment supported/partial/insufficient

5. #127 E2E gate 확장
   - save/resume뿐 아니라 thread restore와 enrichment drift 검증 추가

## 검증 전략

- domain unit: cursor, owner scope, deletion, redaction.
- web boundary: auth/no-store/CSRF, other member 404.
- AI contract: schema validation, evidenceRefs required, insufficient evidence fallback.
- privacy tests: rejected query is not persisted, redacted query does not leak original text.
- E2E: create exploration → complete board → list recent thread → get snapshot → save journey → timeline/saved list distinction.

