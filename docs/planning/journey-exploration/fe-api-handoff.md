# FE 전달서: 선택·제안·서버 상태를 분리한다

상태: 구현 전 계약 초안. 기존 `/api/odii/ask`, 장소/온기/오디 조회의 호환성은 유지한다. 이 문서는 새 화면의 API와 UX 동작을 정하고, OpenAPI 및 JSON Schema로 옮길 때의 기준을 제공한다. 아직 SDK 생성용 전체 OpenAPI 파일은 아니다.

2026-09-10 정합성 보완: [회원·여정 상세안](journey-service-plan.md)에 맞춰 답변 전용 결과와 직접 담기 동작을 확장 제안했다. 회원·공유·익명 이관은 아래 10절의 계약 요구를 함께 적용해야 하며 기존 표만으로 구현 완료를 판단하지 않는다.

2026-09-10 제출 MVP 전송 결정: [7일 MVP FE 전달서](seven-day-mvp-fe-handoff.md)는 브라우저 통신을 REST command + polling + snapshot으로 확정했다. 아래 SSE event·replay 계약은 제출 P0가 아닌 후속 설계다. 이번 구현에서 두 방식을 동시에 만들지 않는다.

## 1. 화면 소유권과 컴포넌트 연결

| 현재 요소 | 재사용/변경 | 소유권과 연결 규칙 |
|---|---|---|
| `JourneyHeroSearch` | 지역·질의·예시 chip 진입점 유지 | FE 입력, BE constraints 해석. 지역이 필요한 방문 탐색만 clarification, 개념 질문은 지역 불필요 |
| `JourneyRefineBar` | 고정 조건 + 재탐색 입력 + 취소 | 기존 보드를 유지하고 proposal 요청, 자동 덮어쓰기 금지 |
| `BentoJourneyGrid` | 고정4장 대신 typed blocks 렌더러 | layout은 FE, 자원 내용은 BE. 알 수 없는 block은 숨기고 호환 경고 |
| 한옥/오디/편집 카드 | 디자인·재생 동작 재사용 | resource type+canonical ID가 동일 선택의 기준 |
| `KnowledgeGraphView` | 기본 축소, 선택 주변 관계만 | 서버 edge 의미/근거, FE x/y와 animation. 지도 좌표로 변환 금지 |
| 기존 지도 | 선택/강조/위치·반경 보기 | WGS84만 지도에 입력, 검증 경로와 의미 관계선을 분리 |
| `SoundConstellationSection` | 기존 지역 SVG·오디오 목록 유지 | story ref와 region ref로 “이 이야기에서 이어 걷기” 연결 |
| 전역 오디오 플레이어 | 기존 재생 상태 유지 | 지도 hover/재추천으로 src 변경 금지. 사용자 재생 클릭만 playback 변경 |

서버 registry는 `placeCard`, `audioCard`, `editorialCard`, `observationCard`, `choiceRail`, `evidencePanel`, `routePreview`를 기본으로 하며, 이번 확장에 `answerBlock`을 제안한다. answerBlock은 plain text와 주장별 evidenceRefs를 갖고 장소 후보 없이 반환할 수 있다. 최초 후보 bundle은 place/audio/choiceRail/evidencePanel부터 시작한다. 추가 block은 계약 버전과 fallback renderer를 같이 배포한다. 임의 HTML/React/JS/CSS, 이벤트 핸들러 문자열, provider URL의 무검증 실행을 허용하지 않는다.

## 2. 구체적인 인터랙션

데스크톱은 지도와 후보 레일을 함께 두고, 선택한 카드의 상세를 아래로 펼친다. 모바일은 하나의 가로 후보 레일과 세로 상세 시트로 단순화한다. 한 제스처 영역에서 가로/세로 무한 스크롤을 경쟁시키지 않는다. 지도 전체화면은 명시적으로 전환한다.

| 행동 | 화면 반응 | 서버 호출 |
|---|---|---|
| 카드 hover | 대응 핀/관계 노드 강조, focus/재생 변경 없음 | 없음 |
| 카드/핀/관계 노드 선택 | 공통 `focusedRef`, 해당 카드로 scroll, 상세1개 확장 | 미로딩 상세만 조회 |
| 같은 카드 다시 선택 | 펼침/접힘만 변경 | 없음 |
| 장소 고정/해제 | pin 표시, 성공 시 새 stateVersion | `POST actions` PIN/UNPIN |
| 관련 이야기 더 보기 | 하위 관계·오디 레일을 세로 확장 | 단순 관계는 GET, 자연어 재해석은 turn |
| “이곳 대신” | 기존 보드를 남기고 교체 대상 지정 | turn에 replaceRefs |
| proposal 도착 | 추가/제거/유지 구분, 적용 전 상태 표시 | 아직 committed board 변경 없음 |
| 적용/취소 | 적용은 새 보드, 취소는 preview 제거 | APPLY_PROPOSAL / DISMISS_PROPOSAL |
| 되돌리기 | 직전 보드 상태를 새 버전으로 복원 | UNDO. version 숫자를 과거로 되감지 않음 |
| 오디 재생 | 플레이어만 해당 음원으로 전환 | 기존 오디오 흐름, 필요 시 유효 이벤트 |

선택 이벤트는 `{ref, origin:'map'|'card'|'graph', interactionId}`로 FE coordinator 한 곳을 지난다. 지도 pan 완료를 다시 선택 이벤트로 발생시키지 않아 피드백 루프를 막는다. pin은 서버의 지속 상태, hover/focus/openPanel은 로컬 표현 상태다. 자원이 삭제되면 placeholder와 이유를 보여주고 재조회 전에는 임의 대체하지 않는다.

접근성: 모든 drag에 버튼 대안, 명확한 focus 순서, 선택 상태 텍스트, 감소된 모션, `aria-live`에는 단계 변경만 전달한다. 토큰마다 읽지 않는다. 핀 수십 개가 키보드 순서를 독점하지 않게 목록 대안을 유지한다.

## 3. HTTP 표면

모든 `/api/v1`은 제안 경로다. session 소유자를 서버가 인증 주체로 결정하며 body의 actorId를 신뢰하지 않는다. UUID는 opaque ID다. 모든 변경 요청에 `Idempotency-Key`와 CSRF 방어를 적용한다. 인증 방식은 FE same-origin BFF + HttpOnly/Secure cookie를 기본 제안으로 한다.

| Method / path | 입력 | 정상 응답 | 주요 실패 |
|---|---|---|---|
| POST `/api/v1/explorations` | locale, regionRef?, anchorRefs? | 201 sessionId, stateVersion=0, snapshotUrl | 400,401,422 잘못된 자원 |
| GET `/api/v1/explorations/{sid}` | 없음 | 200 snapshot + ETag | 401,404 소유권/미존재,410 만료 |
| POST `/api/v1/explorations/{sid}/turns` | clientTurnId, baseVersion, query, replaceRefs?, constraints? | 202 runId, eventsUrl, runUrl, baseVersion | 409 version/active run,422,429 |
| GET `/api/v1/explorations/{sid}/runs/{rid}` | 없음 | 200 run 상태, cursor, proposal/clarification | 401,404 |
| GET `/api/v1/explorations/{sid}/runs/{rid}/events` | Last-Event-ID? | 200 text/event-stream | 401,404,410 cursor 만료 |
| POST `/api/v1/explorations/{sid}/runs/{rid}/cancel` | reason=USER_REQUEST | 200 현재 terminal 또는 CANCELLED | 401,404 |
| POST `/api/v1/explorations/{sid}/actions` | commandId, baseVersion, action union | 200 snapshot 또는 proposal dismissal | 409,422 stale/unknown ref |
| DELETE `/api/v1/explorations/{sid}` | 없음 | 202 삭제 접수 | 401,404 |
| GET `/api/v1/discovery/relations` | resourceType,resourceId,limit<=12,cursor? | 검수 relation/evidence/resource bundle | 400,404 |
| GET `/api/v1/placements/{key}` | regionCode?,locale | edition,mode,status,period,items | 400,404 미정의 key |
| GET `/api/v1/articles/{id}` | locale | 게시된 article revision와 typed blocks | 404 비공개 포함 |
| POST `/api/v1/interaction-events` | clientEventId,sessionRef,eventKind,resourceRef | 202 수집 접수 | 400,401,429 |

`DELETE`는 즉시 접근 차단 후 AI checkpoint/개인 세션 인덱스 등 연관 자료 삭제를 비동기로 완료한다. 익명 세션 기본 보존24시간, 이벤트 replay10분, proposal 유효10분, 비용·장애 추적용 비식별 메타데이터7일을 초기 제품안으로 둔다. 개인정보 정책·법률 판단이 완료된 보존 기간이라는 뜻은 아니며 공개 전 운영 승인이 필요하다.

`interaction-events` 접수202는 순위에 반영됐다는 의미가 아니다. 검증/중복 제거/표본 기준을 거친다. 운영자 Article API는 별도 인증 namespace에서 draft 생성→revision 편집→review 요청→publish 순서로 구현한다. 게시와 편집은 별도 권한, publish는 reviewedRevision과 optimistic version을 요구한다.

### 요청 예제

아래 ID는 계약 설명용 합성 값이다. 실제 관광 사실이나 좌표를 나타내지 않는다.

```json
{
  "clientTurnId": "10000000-0000-4000-8000-000000000001",
  "baseVersion": 3,
  "query": "고정한 한옥은 남기고 다른 이야기를 찾아줘",
  "replaceRefs": [{"type": "STORY", "id": "20000000-0000-4000-8000-000000000002"}],
  "constraints": {"timeBudgetMinutes": 60, "travelMode": "WALK"}
}
```

`query`는 trim 후1~500 Unicode code point로 한정한다. 최초 이야기를 전달할 때 client scriptContext를 사실 근거로 신뢰하지 않는다. `timeBudgetMinutes`는1~480, constraints는 명시적인 허용 키만 받는다. 이동 경로가 없으면 시간 충족은 `UNKNOWN`이고 해당 후보를 “60분 코스 확정”으로 제시하지 않는다. 강제 조건으로 시간이 지정되었는데 확인 불가하면 clarification/자료 부족으로 전환한다.

Action union: PIN/UNPIN은 resourceRef, APPLY_PROPOSAL/DISMISS_PROPOSAL은 proposalId, UNDO는 targetCommandId를 요구한다. 필드 조합을 JSON Schema oneOf로 강제한다. 초기 run이 진행 중이면 cancel 외의 보드 변경은 `409 ACTIVE_RUN`으로 거절한다. 사용자는 “생성 중단 후 수정”을 선택할 수 있고 로컬 focus/재생은 계속 가능하다.

회원·오디 연결 확장: ADD_RESOURCE/REMOVE_RESOURCE를 action 후보로 추가한다. resourceRef와 baseVersion을 검증하고 추가는 현재 공개 자원을 hydrate한다. 동일 자원 추가는 보드 중복 없이 no-op이며 버전을 올리지 않는다. 고정된 자원 제거는 먼저 명시적 UNPIN이 필요하다. 실제 변경은 버전을 1 올리고 active run 중에는 기존과 같이 409다. 신규 계약 버전·fixture 없이 현재 FE가 이 action을 지원한다고 간주하지 않는다.

## 4. Snapshot과 원자적 자원 bundle

Snapshot 필수 필드: schemaVersion, sessionId, stateVersion, board, pinnedRefs, latestRun(nullable), pendingProposal(nullable), updatedAt. latestRun에는 runId/status/lastEventSeq를 포함한다. board는 ref만이 아니라 렌더링에 필요한 공개 자원과 evidence를 함께 포함한다.

ETag는 보드 stateVersion만이 아니라 전체 snapshot 표현의 revision/hash에서 생성한다. run 상태나 pendingProposal 변경만 있어도 ETag가 달라져야 한다. 자료가 비공개/삭제된 경우 snapshot 읽기에서도 현재 공개 정책을 적용하고, 오래된 payload를 그대로 재노출하지 않는다. 보존된 선택이 더 이상 사용 가능하지 않으면 unavailable 표시와 재선택 안내를 제공한다.

Bundle은 다음 최소 계약을 따른다.

| 데이터 | 필드와 불변식 |
|---|---|
| ResourceRef | type=PLACE/STORY/ARTICLE/REGION, id; provider 원본 ID와 구분 |
| Resource | ref,title,publicationRevision,origin,sourceRefs,location nullable. location은 WGS84 longitude/latitude, accuracy/scope 명시 |
| Relation | id,sourceRef,targetRef,type,evidenceRefs; 모든 끝점과 근거가 bundle에 존재 |
| Evidence | id,sourceRef,sourceRevision,kind,summary,asOf,retrievedAt; 링크/인용은 허용 범위 내 |
| Block | id,type,resourceRefs,evidenceRefs,reasonCode; 등록된 type만 허용 |
| ConstraintCheck | constraint,status=SATISFIED/VIOLATED/UNKNOWN,reasonCode,evidenceRefs |
| Proposal | id,baseVersion,expiresAt,keptRefs,addedRefs,removedRefs,bundle,unknowns |

서버는 JSON Schema 통과 후 reference integrity, 중복 ID, evidence 유효성, 공개 권한, 좌표 범위, pinned 보존, 최대 cardinality를 별도 검증한다. Schema 통과만으로 데이터 의미가 옳다고 판단하지 않는다. AI에게서 받은 일부 JSON 조각은 UI card로 그리지 않는다. 후보가 준비되면 완전한 bundle을 한 번에 교체한다.

## 5. SSE의 선택과 이벤트 계약

초기 통신은 **HTTP command + SSE 진행/결과 + HTTP snapshot**이다. 양방향 실시간 협업이 없으므로 WebSocket/gRPC를 브라우저에 도입할 이유가 약하다. LangGraph 내부 stream을 그대로 노출하지 않는다.

SSE는 `text/event-stream` UTF-8, `id/event/data` 프레임을 사용한다. 표준 EventSource 생성자에는 임의 Authorization header 지정 옵션이 없다. 따라서 기본 FE transport는 same-origin **fetch 기반 SSE reader**로 정한다. 상태 코드와 Last-Event-ID를 직접 처리하고 표준 프레임 parser를 검증한다. 나중에 native EventSource로 바꿀 경우에도 인증 cookie와 오류 snapshot 경로가 필요하다. [WHATWG Server-Sent Events](https://html.spec.whatwg.org/multipage/server-sent-events.html)

다음은 OnMaru의 제품 프로토콜 제안이며 SSE 표준이 자동 보장하는 기능은 아니다.

```text
id: 30000000-0000-4000-8000-000000000003:3
event: proposal.ready
data: {"schemaVersion":"1.0","sessionId":"40000000-0000-4000-8000-000000000004","runId":"30000000-0000-4000-8000-000000000003","seq":3,"baseVersion":3,"type":"proposal.ready","payload":{"proposalId":"50000000-0000-4000-8000-000000000005","proposalUrl":"/api/v1/explorations/40000000-0000-4000-8000-000000000004/runs/30000000-0000-4000-8000-000000000003"}}

```

큰 bundle은 `candidates.ready` payload에 원자적으로 전송하거나 동일 run URL에서 읽는다. `proposal.ready`는 이미 저장·검증된 proposal을 가리킨다. URL은 자격 증명 없는 same-origin 상대 경로다. 조회 시 권한을 다시 검사한다.

| event type | 의미 | committed state 변경 |
|---|---|---|
| run.accepted | 서버가 실행과 예산을 접수 | 없음 |
| progress | 단계 SEARCHING/CONNECTING/VALIDATING | 없음 |
| clarification.required | region/constraint 등 명시적 질문 | 없음, 이어서 completed outcome=CLARIFICATION |
| candidates.ready | 검증된 미리보기 bundle | 없음 |
| proposal.ready | 적용 가능한 proposal | 없음 |
| run.completed | 결과 PROPOSAL/ANSWER/CLARIFICATION/NO_RESULTS | 없음 |
| run.failed | 표준 errorCode, retryable, fallbackAction | 없음 |
| run.cancelled | 취소 확정 | 없음 |

run은 `QUEUED→RUNNING→COMPLETED|FAILED|CANCELLED`로만 전이한다. 생성이 완료되어도 제안 적용 전에는 보드 stateVersion이 바뀌지 않는다. APPLY_PROPOSAL/PIN/UNPIN/UNDO와 확장 ADD_RESOURCE/REMOVE_RESOURCE의 실제 변경만 보드 버전을1 증가시킨다. DISMISS_PROPOSAL은 pending proposal을 숨기지만 보드를 바꾸지 않는다. session row transaction으로 최신 run의 completion과 dismissal 기록을 조정한다.

ANSWER 확장에서는 run snapshot의 검증된 result bundle에 answerBlock과 근거를 저장한 뒤 run.completed를 보낸다. FE는 runUrl에서 결과를 읽으며 별도 토큰 스트리밍을 요구하지 않는다. 답변은 보드와 pinnedRefs를 변경하지 않고, 답변 자원·근거의 현재 공개 상태를 조회 시 재검증한다. 답변 기록의 보존·삭제는 회원 여정의 수명과 별도 정책으로 정한다.

Clarification 응답은 같은 run 재개가 아니라 답을 포함한 새 turn이다. 공개 API에 LangGraph interrupt/checkpoint ID를 노출하지 않는다. 첫 후보 결과도 사용자가 “이 후보로 시작”을 적용해야 확정 보드가 된다. 이때 pending preview라는 라벨을 명확하게 표시한다. 확정 보드는 비회원에게도 임시 보존되며 회원 장기 저장과는 별도다. ANSWER에는 후보 적용을 요구하지 않는다.

## 6. 중복·재접속·경쟁 상태

1. 서버는 run_event를 DB에 기록한 뒤 내보낸다. `(runId,seq)`가 안정된 이벤트 ID이고 at-least-once 전송을 전제한다. FE는 이미 처리한 seq를 무시한다.
2. seq는 run 안에서1씩 증가한다. FE가 gap을 발견하면 이후 이벤트 적용을 멈추고 run snapshot을 읽는다. 큰 payload를 놓쳤는데 다음 progress만 보고 성공 처리하지 않는다.
3. run snapshot은 결과와 lastEventSeq를 동일 transaction snapshot에서 읽는다. 그 seq 이후를 replay하여 snapshot/subscribe 사이의 유실을 막는다. 서버는 저장소 replay와 live tail 연결 사이도 동일 순서 cursor로 이어야 한다.
4. 같은 run의 `Last-Event-ID` 이후 이벤트를 반환한다. 다른 run cursor는400, 만료 cursor는410과 runUrl을 제공한다. replay 보존10분 이후에는 snapshot을 다시 읽고 그 high-watermark에서 구독한다.
5. 이미 terminal이고 cursor가 마지막이면204로 종료한다. FE는 terminal 수신 시 stream을 닫고 무한 재접속하지 않는다. 네트워크 끊김은 run 취소와 같지 않다. 비용 제한30초 안에서 실행은 계속될 수 있으며 명시적인 cancel을 제공한다.
6. 같은 actor+Idempotency-Key에 같은 payload면 기존 run/응답을 반환한다. 다른 payload면409. 기존 run 명령의 등록 유효기간은24시간을 초기안으로 하되 회원 여정 보존 기간과 동일시하지 않는다. 만료 후 자동 재제출을 보장하지 않는다. 익명 이관·공유·탈퇴 등 신규 명령은 별도 중복 처리 계약을 정의해야 한다.
7. 세션에는 active run 하나만 허용한다. 새 turn 또는 보드 변경은 active run이 있으면409. cancel과 completion의 경합은 조건부 update로 하나의 terminal만 채택한다. 취소가 이미 완료를 이기지 못했으면200 COMPLETED를 돌려주며 취소 성공으로 위장하지 않는다.
8. proposal의 baseVersion이 현재와 다르거나 만료되면 적용409. 다른 탭의 변경은 focus 복귀/액션 전 snapshot 갱신과409 recovery로 처리한다. 초기 버전은 다중 탭 실시간 협업을 보장하지 않는다.
9. keep-alive comment를15초마다 보내고 proxy buffering을 비활성화한다. 최대 payload256KiB를 초기 제한으로 두고 초과 bundle은 HTTP 조회로 전환한다. 느린 consumer는 연결 종료 후 replay로 복구한다.
10. stream header 전 오류는 HTTP JSON, 이후 오류는 `run.failed` 이벤트다. gateway 단절로 오류 이벤트도 못 받으면 FE는 상태 조회로 판별한다. `500` 문구를 SSE 정상 data처럼 보내지 않는다.

## 7. 추천·주간·게시물 응답

placement key 예: `HANOK_RECOMMENDED`, `ODII_RECOMMENDED`, `ODII_WEEKLY_EDITORIAL`, `HANOK_WEEKLY_POPULAR`. 타입을 분리해 인기 데이터 부족을 편집 순위로 오인하지 않게 한다.

```json
{
  "placement": "ODII_WEEKLY_POPULAR",
  "mode": "BEHAVIORAL_RANKING",
  "origin": "DERIVED_INDEX",
  "status": "INSUFFICIENT_SAMPLE",
  "asOf": "2026-09-07T02:00:00+09:00",
  "period": {"start": "2026-08-31T00:00:00+09:00", "endExclusive": "2026-09-07T00:00:00+09:00", "timezone": "Asia/Seoul"},
  "rankingVersion": "weekly-v1-draft",
  "editionId": null,
  "items": [],
  "fallbackPlacement": "ODII_WEEKLY_EDITORIAL"
}
```

기간은 계약 설명용 예제다. 실제 순위 집계를 실행한 결과가 아니다. EDITORIAL 응답은 publishedAt/editionRevision/editorialReason, BEHAVIORAL_RANKING은 집계 정책·대상 기간·근거 종류를 포함한다. 원시 사용자별 행동은 공개하지 않는다.

Article의 블록은 heading/paragraph/image/resourceCollection/audioReference 같은 제한된 편집 구조다. 출처/자원 reference는 revision에 귀속한다. 외부 HTML을 그대로 렌더링하지 않는다. 게시 이후 원본 삭제는 관련 block unavailable 처리와 운영자 재검수로 이어지며 전혀 다른 장소를 자동 연결하지 않는다.

## 8. 오류 매핑과 FE 행동

| 코드 | HTTP/이벤트 | FE 행동 |
|---|---|---|
| INVALID_INPUT | 400/422 | 해당 필드 안내, 재시도 자동화 없음 |
| AUTH_REQUIRED | 401 | 기존 보드 유지, 세션 복구/로그인 안내 |
| VERSION_CONFLICT | 409 | snapshot 갱신 후 변경 비교. 사용자 명령 자동 재적용 금지 |
| ACTIVE_RUN | 409 | 진행 중 run으로 복귀 또는 취소 후 새 요청 |
| QUOTA_EXCEEDED | 429 | retryAfter 안내, 일반 필터/편집판 제공 |
| AI_UNAVAILABLE / AI_TIMEOUT | failed | 마지막 committed board 유지, 수동 재시도와 검색 제공 |
| EVIDENCE_UNAVAILABLE | failed 또는 일부 후보 제외 | 근거 없는 답을 채우지 않음 |
| NO_RESULTS | completed outcome | 조건 완화·지역 변경 질문, 시스템 장애와 구별 |
| RESOURCE_WITHDRAWN | 422 적용 실패 | 최신 자원 재조회 후 사용자가 재선택 |

traceId는 지원·운영용이고 비밀 토큰이 아니다. 사용자에게 내부 stack, provider key, system prompt, checkpoint 원문을 노출하지 않는다.

## 9. FE/BE 공동 완료 체크리스트

- 정상, 빈 결과, 좌표 없음, 오디오 없음, stale 통계, 삭제된 근거, 혼잡 미확인 fixture를 공유한다.
- OpenAPI request/response와 event JSON Schema를 고정하고 FE 타입 생성 및 BE serializer 계약 테스트를 연결한다.
- selection coordinator와 SSE reducer를 네트워크/AI 없이 fixture로 시험한다.
- 첫 클릭 후 지도/카드/오디오의 선택 일치, 키보드 조작, 모바일 상세 시트 동작을 E2E로 검증한다.
- 중복 seq, gap,410, disconnect, cancel/completion 경합, multi-tab409를 자동화한다.
- 보드 snapshot에 private response cache가 적용되고 다른 actor session 접근은404로 차단되는지 확인한다.

이 문서의 프로토콜은 계약 리뷰의 출발점이다. JSON Schema/OpenAPI 생성·검증과 FE 실행 테스트가 끝나야 “API 명세 확정”으로 승격한다.

## 10. 회원·공유 확장 계약의 필수 경계

- 웹의 cookie/BFF 제안은 네이티브 앱 인증 계약을 확정하지 않는다. 공통 내부 회원 ID와 서버 소유권 검사 원칙은 유지하고 앱의 인증 전달·복구 방식은 별도 명세한다.
- 비회원은 서버가 발급·검증한 익명 세션으로 임시 탐색을 소유한다. 인증되지 않은 임의 actorId로 생성·조회하지 않는다. 회원 전용 장기 저장·공유와 공개 열람을 구분한다.
- 익명 이관은 active run 종료 후 확정 선택을 새 회원 여정으로 복사하는 안이다. 원본 권한·baseVersion·대상 회원을 확인하며 중복 복사는 같은 결과를 반환한다. 성공 후 익명 원본 접근과 남은 실행을 폐기하고 기존 스트림도 차단한다. pending proposal·checkpoint는 이관하지 않는다.
- 회원 여정 저장과 보드 적용은 별도 계약이다. 첫 장기 저장 후 명시적 보드 변경은 자동 저장하되 preview를 확정하지 않는다. 재생 위치·회원 프로필·북마크 버전은 보드 stateVersion과 분리한다.
- 읽기 전용 공유는 인증 세션 ID를 공개하는 동작이 아니다. 별도 공유 권한·현재 공개 자원만 반환하고 질문·기록은 제외한다. 원본 편집은 기존 공유판을 바꾸지 않으며 새 공유판 발행을 명시적으로 수행한다. 철회·탈퇴·원천 비공개 전환은 이후 서버 조회와 캐시 응답에도 적용한다. 이미 수신자가 저장한 복사본까지 회수한다고 보장하지 않는다.
- 인증 세션 만료·로그아웃·탈퇴 후 기존 SSE 연결도 더 이상 개인 데이터를 내보내지 않아야 한다. 탈퇴는 run 취소 및 늦은 결과 재저장 차단을 포함한다. 재접속은 snapshot과 현재 권한을 다시 검사한다.
- 위 계약과 회원 가입·프로필·목록·삭제·북마크·공유 API는 아직 HTTP 경로/Schema 미정이다. OpenAPI 작성 전 정상·중복·만료·충돌·접근 거부 fixture를 정의한다. 24시간 익명 보존안을 회원 장기 저장에 적용하지 않는다.
