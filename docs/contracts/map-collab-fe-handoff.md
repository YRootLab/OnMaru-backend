# 지도 공동 편집: FE 연동 작업 프롬프트

2026-11-09 기준. 백엔드 계약이 확정된 뒤 FE 작업자(또는 FE 에이전트)에게 그대로 전달하는 프롬프트다. 계약 원본은 BE 레포의 아래 파일이다. 충돌하면 OpenAPI와 JSON Schema 파일이 우선한다.

### 전달 패키지

| 용도 | 파일 |
|---|---|
| REST 정식 계약 (OpenAPI 3.1) | [openapi/map-collab.openapi.yaml](openapi/map-collab.openapi.yaml) |
| REST 설명·근거 | [map-collab-api.md](map-collab-api.md) |
| WS 메시지 정식 계약 (JSON Schema) | [schemas/map-collab-ws-message.schema.json](schemas/map-collab-ws-message.schema.json) |
| REST 예시 응답 (성공·오류, 26개) | [fixtures/map-collab/](fixtures/map-collab/) |
| WS 예시 메시지 (정상 13개) | [fixtures/map-collab/ws/](fixtures/map-collab/ws/) |
| WS 거절 예시 (스키마 위반 5개) | [fixtures/map-collab/ws/invalid/](fixtures/map-collab/ws/invalid/) |
| 도메인 규칙·기본값 D1~D10 | [../planning/map-collab/domain-rules-and-backend-plan.md](../planning/map-collab/domain-rules-and-backend-plan.md) |

**FE 목(mock) 사용법**
- REST: 각 fixture의 `request`(method, path, body)와 `response`(status, headers, body)를 MSW 같은 도구의 핸들러로 옮긴다. 같은 파일이 BE 검증에도 쓰이므로 목과 실제 계약이 어긋나지 않는다.
- WS: 서버 메시지 fixture를 연결 후 순서대로 보내면 reducer를 테스트할 수 있다. `invalid/` 파일은 클라이언트가 무시하거나 거절해야 하는 메시지다.
- 생성된 타입: OpenAPI에서 TypeScript 타입을 생성하고, WS 메시지는 JSON Schema에서 zod 스키마를 만들 수 있다. 생성 도구는 FE 팀이 정한다.

## 전제 조건 (BE 쪽이 먼저 끝나야 하는 것)

- P0: `GET /api/v1/members/me` 401 문제(#781) 해결
- Phase 3 완료: REST와 WS가 staging에서 계약대로 동작

FE는 Phase 3 전까지 **계약 기반 목(mock)** 으로 개발할 수 있다. 실제 연결 검증은 staging에서 한다.

---

## 프롬프트 (그대로 전달)

```text
너는 OnMaru 프론트엔드(Next.js App Router, React, Emotion, Kakao Map) 작업자다.
지도 공동 편집 기능을 Liveblocks 기반에서 백엔드 WebSocket 기반으로 전환한다.
백엔드 계약은 BE 레포의 docs/contracts/map-collab-api.md 와
docs/contracts/schemas/map-collab-ws-message.schema.json 이다. 이 두 파일을 먼저 읽고,
계약과 다르게 구현하지 마라. 계약이 모호하면 구현을 멈추고 질문해라.

## 배경
- 현재 구현은 Liveblocks(@liveblocks/client, @liveblocks/react, @liveblocks/node)에 의존하고,
  /api/liveblocks-auth Next.js 라우트로 토큰을 발급한다.
- 백엔드가 방·초대·참여자·핀·실시간 채널을 직접 제공한다. 외부 SaaS 의존을 없앤다.
- 방 식별자 대신 초대 토큰으로 입장한다. 방 UUID를 입장 권한으로 쓰지 않는다.

## 1. 제거할 것
- 패키지: @liveblocks/client, @liveblocks/react, @liveblocks/node
- 라우트: src/app/api/liveblocks-auth/route.ts
- 파일: src/features/map/collaboration/liveblocks.config.ts
- 환경 변수: LIVEBLOCKS_SECRET_KEY, LIVEBLOCKS_SKIP_BACKEND_AUTH, NEXT_PUBLIC_LIVEBLOCKS_ENABLED
  (그 자리에 NEXT_PUBLIC_MAP_COLLAB_ENABLED 를 둔다. 기본 false. 기존 .env.example 과 env-contract 검증 스크립트도 함께 갱신한다)
- MapCollabProvider의 LiveblocksProvider/RoomProvider 구조, useCollabPins의 useMutation/useStorage

## 2. 초대 흐름
- "친구 초대" 버튼(ShareCollabButton):
  1) 방이 없으면 POST /api/v1/map-rooms → 응답의 invite.shareUrl 을 클립보드에 복사
  2) 방이 이미 있으면 POST /api/v1/map-rooms/{roomId}/invites → 새 shareUrl 복사
  3) 방 ID는 URL에 `?room={roomId}`로 유지한다 (새로고침 후 복귀용). 초대 토큰은 URL에 남기지 않는다.
- 초대 링크 진입 (/map?invite=TOKEN):
  1) 로그인 안 됨 → 카카오 로그인 후 같은 invite URL로 돌아오게 한다 (기존 "공유 링크 URL 보존" 패턴 재사용)
  2) 로그인 됨 → POST /api/v1/map-rooms/joins {token} → 성공하면 router.replace(`/map?room=${room.id}`)
  3) 오류 처리는 아래 표를 따른다. 토큰은 콘솔·에러 리포팅·analytics에 기록하지 않는다.
- 예전 형식 링크 (/map?room=UUID 만 있고 멤버십이 없는 경우): WS 핸드셰이크가 403 또는 404 를 주면
  "이 링크는 더 이상 사용할 수 없어요. 새 초대 링크를 받아 주세요" 안내를 띄운다.

## 3. 입장 후 상태 동기화
1. GET /api/v1/map-rooms/{roomId} → 방 상태와 멤버 목록 (참여자 패널)
2. GET /api/v1/map-rooms/{roomId}/snapshot → 활성 핀 목록 (order 오름차순) 과 lastSeq
3. WS 연결: wss://api.onmaru.site/api/v1/map-rooms/{roomId}/ws?lastSeq={lastSeq}
   - WELCOME 을 받으면 상태를 WELCOME 기준으로 교체한다.
   - 실제 API 호스트는 NEXT_PUBLIC_API_URL 에서 만든다. 하드코딩하지 않는다.

## 4. WebSocket 클라이언트 (새 모듈 권장: src/features/map/collaboration/socket/)
- 메시지 봉투: `{ v: 1, type, opId?, payload }`. 수신 메시지는 zod 등으로 스키마 검증하고, 실패하면 버린다.
- 상태 전이 (reducer 한 곳에서만 처리):
  - WELCOME: 상태 전체 교체, lastSeq 저장
  - EVENT: seq <= lastSeq 면 무시(중복). seq == lastSeq+1 이면 적용하고 lastSeq 갱신. 그보다 크면 연속성이 깨진 것이므로 재연결 (lastSeq 로 재생 요청)
  - ACK: opId 에 해당하는 pending 명령을 확정. 낙관적으로 넣은 핀은 pin 값으로 교체
  - ERROR: opId 가 있으면 pending 을 롤백하고 오류 메시지 표시
  - CURSORS: memberId 별 커서 갱신. lat 이 null 이면 제거
  - PRESENCE: 온라인 상태 갱신
  - RESYNC_REQUIRED: GET .../snapshot 으로 상태 재구성 후 WS 재연결
- 재연결: 지수 백오프 (1s, 2s, 4s … 최대 30s). 마지막 seq 를 lastSeq 로 보낸다.
- 종료 코드 처리:
  - 4000: 재연결하지 않는다 (클라이언트 버그로 기록)
  - 4001: 로그인 만료 안내 후 로그인 흐름
  - 4003, 4004, 4010: 재연결하지 않고 방 밖으로 나가는 안내
  - 4029, 4030: 재연결
- 브라우저 탭이 숨겨졌다가 돌아오면 연결 상태를 확인하고 필요하면 재연결한다.

## 5. 핀
- 추가: PIN_ADD 는 payload 에 placeId 만 보낸다. placeName, lat, lng 는 보내지 않는다 (서버가 카탈로그에서 결정한다).
  낙관적으로 목록에 넣고 opId 를 pending 에 둔다.
- 제거: PIN_REMOVE {pinId}. 핀 목록의 pinId 를 쓴다. placeId 로 "첫 번째 것"을 지우던 로직을 버린다.
- "친구와 핀 공유" / "핀 취소" 버튼 표시는 **방 기준**이다. 같은 장소의 활성 핀이 방 안에 있으면 "핀 취소", 없으면 "친구와 핀 공유".
  기존처럼 사용자 ID 기준으로 판단하지 않는다 (새로고침 후 어긋나는 원인이었다).
- 선 그리기: 활성 핀을 order 오름차순으로 정렬해 Polyline 을 그린다. 스타일(점선, 색)은 현재 구현을 유지한다.
- 핀 마커 색은 `pin.addedBy` 기준 해시로 정한다. 익명 UUID 기반 해시를 쓰지 않는다.

## 6. 커서
- 마우스 이동은 최대 20Hz(50ms 간격 쓰로틀)로 CURSOR {lat, lng} 를 보낸다.
- 지도 밖으로 나가면 CURSOR {lat: null, lng: null} 을 보낸다.
- 서버는 CURSORS 묶음으로 보낸다. 수신한 값을 그대로 렌더링하고, 자신의 커서는 그리지 않는다.

## 7. 참여자 패널
- 목록: GET /api/v1/map-rooms/{roomId} 의 members + PRESENCE 로 online 갱신 + 새 멤버는 WS EVENT(MEMBER_JOINED)로 추가
- 표시: "N명 참여 중" = 멤버 수. 온라인 표시는 online 필드 기준.
- useOthers / useSelf 같은 Liveblocks 훅을 쓰지 않는다.

## 8. 오류 코드 → 사용자 문구

| code | 상황 | 문구 (예) |
|---|---|---|
| INVITE_NOT_FOUND | 초대 링크 무효·만료·철회 | 초대 링크가 유효하지 않아요. 새 링크를 받아 주세요 |
| ROOM_EXPIRED / ROOM_CLOSED | 방 만료·종료 | 이 지도는 기간이 끝났어요 |
| ROOM_FULL | 인원 초과 | 지도 인원이 가득 찼어요 (20명) |
| FORBIDDEN | 멤버 아님 | 이 지도에 참여할 권한이 없어요 |
| PLACE_NOT_FOUND | 장소 없음 | 이 장소는 지도에 추가할 수 없어요 |
| PIN_ALREADY_EXISTS | 같은 장소 중복 | 이미 이 지도에 찍힌 장소예요 (응답의 기존 핀을 목록에 반영) |
| PIN_LIMIT_EXCEEDED | 핀 100개 초과 | 핀은 최대 100개까지 찍을 수 있어요 |
| PIN_NOT_FOUND | 이미 제거됨 | 이미 지워진 핀이에요 |
| RATE_LIMITED | 빈도 초과 | 잠시 후 다시 시도해 주세요 |
| VALIDATION_ERROR, INTERNAL_ERROR | 기타 | 문제가 생겼어요. 다시 시도해 주세요 |

## 9. 하지 말 것
- 사용자 입력이나 presence 의 닉네임·좌표를 신뢰해서 서버 상태처럼 쓰지 않는다. 서버 메시지만 상태의 근거다.
- 초대 토큰을 localStorage·sessionStorage 에 저장하지 않는다. (URL 진입 직후 join 에 쓰고 버린다)
- 방 ID 를 입장 권한처럼 취급하지 않는다. 권한은 항상 서버가 판단한다.
- 커서를 상태 저장소에 넣어 재렌더링을 늘리지 않는다. 커서는 별도 구독 채널로 다룬다.

## 10. 테스트 (완료 기준)
- 단위: reducer (seq 중복, 갭, ACK 확정, ERROR 롤백, PIN_ALREADY_EXISTS 반영)
- 단위: 재연결 백오프 계산, 종료 코드별 동작, 메시지 스키마 검증 실패 시 무시
- 단위: 초대 진입 흐름 (로그인 전후, join 성공·실패, 토큰 미저장 확인)
- 컴포넌트: 핀 버튼 상태가 방 기준으로 정해지는지 (새로고침 전후 동일)
- e2e (staging): 두 계정이 같은 방에서 핀 추가·제거, 한쪽 네트워크 차단 후 복구, 선 순서 일치
- Lint, 타입체크, 기존 테스트 전부 통과

## 완료 체크리스트
- [ ] Liveblocks 패키지·라우트·파일·환경 변수 제거, .env.example 과 검증 스크립트 갱신
- [ ] 초대 생성·진입 흐름 (토큰 URL, join, 오류 문구)
- [ ] WS 클라이언트 (재연결, lastSeq, 종료 코드, 스키마 검증)
- [ ] 핀 추가·제거 (placeId 만 전송, 방 기준 버튼 상태, PIN_ALREADY_EXISTS 처리)
- [ ] 선 그리기 (order 기준)
- [ ] 커서 (20Hz 쓰로틀, null 처리)
- [ ] 참여자 패널 (REST + PRESENCE + MEMBER_JOINED)
- [ ] 예전 ?room= 링크 안내
- [ ] 테스트 전부 + staging e2e
```

---

## BE가 FE에 확인받을 사항 (FE 답변 대기)

1. 예전 `?room=UUID` 링크를 완전히 막고 안내만 할지, 짧은 유예 기간을 둘지
2. 초대 링크 공유 시 로그인 후 복귀 흐름이 현재 구현과 같은 방식으로 되는지
3. 커서 20Hz 쓰로틀이 FE 렌더링 비용에 맞는지 (렌더 빈도 확인)
4. 핀 제거 권한을 멤버 누구나로 둘지 (D4)
