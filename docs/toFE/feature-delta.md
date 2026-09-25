# FE Feature Delta

## 목적

이 문서는 기존 `docs/specs`와 현재 FE 프로토타입에 없던 백엔드 기반 기능을 화면 관점에서 정리한다. FE는 기존 mock/localStorage 데이터를 서버 정답으로 승격하지 않으며, 아래 API DTO와 상태를 추가한다.

## `/discover`: AI 여정 탐색

| 항목 | 기존 FE/기획 | 현재 전달 기준 |
|---|---|---|
| 실행 | local mock/timer 또는 과거 polling 초안 | `POST` command 후 `eventsUrl` SSE 구독, GET snapshot 복구 |
| 표시 | 토큰/임의 응답 금지 | `run.stage`만 짧게 표시, `run.terminal` 뒤 snapshot 렌더 |
| 연결 끊김 | UI 상태에 의존 | reconnect, `reset`, 탭 복귀 뒤 GET run+exploration |
| Gemini 실패 | 빈 화면 또는 오류 가능 | 이미 검증된 후보가 있으면 같은 run `engine=BASELINE` 완료, “기본 탐색 결과” 표시 |
| 지역 없음 | 전국 임의 결과 위험 | 한 번의 typed clarification을 렌더 |
| 변경 제안 | 기존 board 즉시 교체 금지 | `pendingProposal`의 유지/추가/제외를 먼저 보여주고 APPLY/DISMISS |

FE는 `RunAccepted.eventsUrl`을 사용하고, `run.stage`, `run.terminal`, `heartbeat`, `reset`만 처리한다. SSE payload로 board를 그리지 않는다. terminal/reset/reconnect 후 `GET /explorations/{id}/runs/{runId}`와 `GET /explorations/{id}`를 읽어 서버 snapshot으로 화면을 맞춘다. SSE를 지원할 수 없는 환경에서만 1초 GET polling fallback을 terminal까지 사용한다.

`engine=BASELINE`과 non-null `degradedReason`은 운영 장애 문구가 아니다. 화면에는 검수된 후보 기반의 “기본 탐색 결과”만 표시한다. 지역·주제 해석 실패, 후보 없음, 취소, 정책 거절은 baseline으로 꾸며 표시하지 않는다.

## `/map`: VisitReview로 전환

기존 `MAP-F003`의 localStorage 피드는 사용하지 않는다. 지도 온기 후기 UI는 VisitReview를 사용하며, VisitReview는 한옥·한옥 숙박·한옥 카페·한옥 체험·전통시장에 대한 300자/5줄 짧은 공개 후기다. `mood`(`북적`/`한적`), 선택 `score`(1..5), 선택 `tags`(최대 5개)를 함께 제공한다. `visitorCount`는 후기 장소 지역의 활성 DataLab revision 최신 외지인 일 관측값이며 결측이면 `null`이다. DataLab 원천이 소수를 주는 경우 공개 정수 계약에 맞춰 가장 가까운 1명으로 반올림한다. 댓글, 대댓글, 사진은 이번 범위에 없다.

1. 전국 진입 시 `GET /visit-review-regions`으로 시·도 count marker만 받는다.
2. 시·도를 선택하면 해당 시·군·구 집계를 읽는다.
3. 지도 drag/zoom은 시각 표현만 바꾸며 목록 요청을 하지 않는다.
4. 사용자가 `이 지역 후기 보기`를 선택할 때만 `scope=REGION` 목록과 핀을 읽는다.
5. 성공한 응답으로만 기존 목록과 핀을 함께 교체한다. 늦은 응답은 `AbortController`와 request sequence로 버린다.

`NEARBY`, `VIEWPORT`, radius, bbox 기반 후기 본문 요청은 더 이상 사용하지 않는다. 위치 동의가 있는 경우에도 `/regions/resolve`로 행정구역을 찾은 뒤 REGION 목록을 사용한다.

후기 작성창에는 개인정보·연락처·주소·광고성 내용 금지 안내와 신고 진입점을 둔다. 좋아요는 toggle이 아니라 의도 상태를 보내는 `PUT /likes/me`, `DELETE /likes/me`다. optimistic UI는 실패 시 복구한다.

## 한옥·지도·Odii가 함께 쓰는 관광 장소 찜

찜은 한옥 페이지에만 붙는 하트가 아니다. 사용자가 한옥 목록, 한옥 상세, 지도, Odii 연결 장소에서 발견한 **공개 관광 장소**를 다시 찾기 위한 공통 기능이다. FE는 관광공사 원본 `contentId`가 아니라 backend가 준 canonical `placeId`만 사용한다.

```text
한옥 상세의 장소 카드 ─┐
지도 관광지 카드      ─┼─ same canonical placeId ─> 회원의 PLACE 찜
Odii 연결 장소 카드   ─┘                                  └─ 내 정보 월간 타임라인
```

### 사용자가 보는 흐름

1. 한옥 목록이나 지도에서 마음에 드는 장소의 하트를 누른다.
2. 로그인 회원이면 `PUT /saved-resources/places/{placeId}`가 성공하고, 같은 장소가 다른 화면에서도 `savedByMe:true`로 표시된다.
3. 비회원이면 로그인 안내를 보여 준다. FE는 장소 ID와 “찜하려 했다”는 intent만 유지한다.
4. Kakao 로그인 성공 뒤 그 장소가 아직 공개 상태이면 같은 PUT을 한 번 보낸다. 로그인 취소/실패이면 아무 서버 저장도 생기지 않는다.
5. 내 정보 화면에는 찜한 날짜, 장소명, 카테고리, 지역, 대표 이미지가 월별로 나타난다.

장소의 운영시간, 이미지 한 장, 관광공사 원본 데이터 행을 각각 저장하지 않는다. 사용자가 저장하는 것은 “경복궁”, “전주 한옥마을”, “한옥 카페” 같은 장소 자체다. source 데이터가 갱신되면 현재 공개 장소 정보는 갱신될 수 있지만, 찜의 대상인 canonical `placeId`는 유지된다.

### 화면별 규칙

| 화면 | 찜 버튼을 두는 위치 | 저장되는 값 | 주의점 |
|---|---|---|---|
| 한옥 목록 | 각 카드의 접근 가능한 하트 버튼 | canonical `placeId` | 목록 재조회 후 `savedByMe`로 상태를 다시 그린다. |
| 한옥 상세 | 제목/대표 이미지 영역 | canonical `placeId` | 상세의 운영정보·이미지에는 개별 찜 버튼을 만들지 않는다. |
| 지도 | 장소 상세 sheet 또는 선택 카드 | canonical `placeId` | 후기 핀과 장소 찜을 혼동하지 않는다. 후기 목록은 별도 기능이다. |
| Odii | 검수된 연결 장소 카드 | canonical `placeId` | 이야기 자체가 아니라 발견한 관광 장소를 기본으로 저장한다. |
| Odii 이야기 | 오디오 재생 정보 | `storyId`의 보조 저장 | 장소 찜과 별개로 다시 듣기 목적일 때 `ODII_STORY`를 사용할 수 있다. |

## 내 정보: “내가 이번 달에 찜한 장소”를 보여주기

내 정보의 첫 화면은 단순한 장소 목록이 아니라, 사용자가 여행을 준비하며 무엇을 모았는지 보여 주는 월간 기록이다. `GET /me/timeline`의 `SAVED_PLACE` item은 장소명뿐 아니라 장소 category, 지역, 찜한 날짜를 렌더한다.

예시 화면 문구:

```text
2026년 9월
  9월 13일
    ♥ 전주 한옥마을 · 한옥 · 전주
    ♥ 교동 다원 · 한옥 카페 · 전주
  9월 10일
    ♥ 남산골 한옥마을 · 관광지 · 서울
```

장소를 누르면 현재 공개된 장소 상세로 간다. 사용자가 타임라인에서 “찜 해제”를 누르면 `DELETE /saved-resources/places/{placeId}`를 호출하고 해당 기록은 다음 조회에서 사라진다. 비공개·삭제된 장소는 과거 이름이나 이미지를 추측해 보여 주지 않고, 월별 `unavailableCount`로만 알릴 수 있다.

## 인증·개인 상태 공통 규칙

- OAuth access/refresh token, Kakao client secret을 localStorage에 저장하지 않는다.
- cookie 기반 POST/PUT/DELETE에는 `/auth/csrf`의 `X-CSRF-TOKEN`을 붙인다.
- 서버 opaque member ID, provider subject/email은 UI 비교 키나 공개 표시 값으로 사용하지 않는다. `mine`, `likedByMe`, `savedByMe`를 사용한다.
- 개인 응답은 `Cache-Control: no-store`로 다루며, 로그아웃/탈퇴 후 이전 private snapshot을 다시 렌더하지 않는다.
- cursor 목록은 `items`, `hasMore`, `nextCursor`만 사용한다. `hasMore:false`면 빈 페이지를 추가 요청하지 않는다.

## FE가 새로 가져야 할 사용자 상태

| 서버 상태 | FE 화면 상태 |
|---|---|
| `CLARIFICATION_REQUIRED` | 질문과 선택지, 자유 입력 허용 여부 |
| active run | generating + cancel 가능, action/save 비활성 또는 409 처리 |
| `pendingProposal` | proposal preview, APPLY/DISMISS |
| `engine=BASELINE` | 기본 탐색 결과 라벨 |
| `HIDDEN`/`REMOVED` review | 목록에서 제거, 상세 요청은 404 처리 |
| login cancel | 기존 guest exploration/board 유지 |
