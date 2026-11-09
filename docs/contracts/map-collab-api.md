# 지도 공동 편집(Map Collab) API 계약

2026-11-09 기준 설계 계약. 구현 완료를 의미하지 않는다. 기존 `/api/v1`, HTTPS JSON, 공통 오류 형식(`schemaVersion`, `code`, `message`, `requestId`, `details`)을 따른다. 실시간 채널은 WebSocket이며 기존 지도 REST 계약의 "지도에는 SSE와 polling을 쓰지 않는다"는 규칙은 지도 **데이터** 조회에 대한 것이고, 공동 편집 실시간 채널은 이 문서가 예외로 정의한다.

관련 문서
- 도메인 규칙·상한·BE 작업 분해: [domain-rules-and-backend-plan.md](../planning/map-collab/domain-rules-and-backend-plan.md)
- 메시지 JSON Schema: [map-collab-ws-message.schema.json](schemas/map-collab-ws-message.schema.json)
- FE 작업 프롬프트: [map-collab-fe-handoff.md](map-collab-fe-handoff.md)
- 스키마: `apps/spring-api/src/main/resources/db/migration/baseline/V058__map_collab_schema.sql`
- 저장 전략: [ADR-0020](../decisions/0020-map-collab-room-state-in-memory.md)

## 공통 규칙

- 모든 요청은 로그인 세션 쿠키가 필요하다. 비로그인은 `401 UNAUTHORIZED`.
- 상태 변경 요청(POST/DELETE)은 기존 CSRF 규칙(`CsrfProtectionFilter`)을 따른다. 즉 CSRF 헤더와 허용 Origin이 필요하다.
- WebSocket 핸드셰이크는 GET이라 CSRF 필터 대상이 아니다. 따라서 서버가 **Origin을 `CorsOriginPolicy`로 직접 검사**한다.
- 모든 응답 `schemaVersion`은 `"1.2"`를 쓴다. 공동 편집 전용 WS 메시지는 봉투의 `v: 1`로 버전을 관리한다.
- 시간은 ISO-8601 UTC 문자열(`2026-11-12T03:00:00Z`).
- 장소 식별자는 항상 카탈로그 공개 ID(`p-...`)다. 장소 이름과 좌표는 **클라이언트가 보내지 않는다.** 서버가 카탈로그에서 가져온다.

### 오류 코드 (이 계약에서 추가되는 것)

| code | HTTP | 의미 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 형식 오류 (기존 코드) |
| `UNAUTHORIZED` | 401 | 로그인 필요 (기존 코드) |
| `FORBIDDEN` | 403 | 방 멤버가 아님 |
| `INVITE_NOT_FOUND` | 404 | 초대 토큰이 없거나 만료·철회됨 (존재 여부를 구분하지 않음) |
| `ROOM_NOT_FOUND` | 404 | 방이 없음 (멤버가 아닌 경우에도 존재 여부를 노출하지 않으려면 `FORBIDDEN` 대신 이 코드를 쓸 수 있음 — 결정 사항) |
| `ROOM_EXPIRED` | 410 | 방 만료 (72시간 경과) |
| `ROOM_CLOSED` | 410 | 방 종료 |
| `ROOM_FULL` | 409 | 방 인원 상한(20) 초과 |
| `INVITE_LIMIT_EXCEEDED` | 409 | 활성 초대 링크 상한(5) 초과 |
| `PLACE_NOT_FOUND` | 404 | 카탈로그에 없는 `placeId` |
| `PIN_ALREADY_EXISTS` | 409 | 같은 방에 같은 장소의 활성 핀이 이미 있음. 응답에 기존 핀을 포함 |
| `PIN_LIMIT_EXCEEDED` | 409 | 방 활성 핀 상한(100) 초과 |
| `PIN_NOT_FOUND` | 404 | 제거하려는 핀이 없거나 이미 제거됨 |
| `OP_CONFLICT` | 409 | 같은 `opId`가 다른 내용으로 재사용됨 |
| `RATE_LIMITED` | 429 | 요청 빈도 초과 (기존 코드) |

## REST 엔드포인트

> 정식 계약은 [openapi/map-collab.openapi.yaml](openapi/map-collab.openapi.yaml)이다. 이 절은 설명용이며, 둘이 다르면 OpenAPI 파일을 따른다. 예시 응답은 [fixtures/map-collab](fixtures/map-collab/)에 있다.

### 방 생성 (초대 링크 포함)

`POST /api/v1/map-rooms`

요청: 바디 없음.

응답 `201`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "8f3c...uuid", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z", "role": "OWNER" },
  "invite": {
    "token": "raw-token-shown-once",
    "shareUrl": "https://onmaru.site/map?invite=raw-token-shown-once",
    "expiresAt": "2026-11-12T03:00:00Z"
  }
}
```

- 원문 `token`은 이 응답에서 **한 번만** 노출된다. DB에는 해시(`token_hash`)만 저장된다.
- `invite.expiresAt`은 방 `expiresAt`을 넘지 않는다.

### 초대 링크 추가 발급

`POST /api/v1/map-rooms/{roomId}/invites` (멤버 누구나 가능)

응답 `201`: `{ "schemaVersion": "1.2", "invite": { "token", "shareUrl", "expiresAt" } }`

- 활성 초대 상한 5개. 초과 시 `INVITE_LIMIT_EXCEEDED`.
- 이전에 발급한 링크는 만료 전까지 유효하다. (해시만 저장하므로 기존 원문을 다시 보여 줄 수 없음)

### 초대 철회

`DELETE /api/v1/map-rooms/{roomId}/invites/{inviteId}` (발급자 또는 OWNER)

응답 `204`.

### 초대로 입장

`POST /api/v1/map-rooms/joins`

요청
```json
{ "token": "raw-token" }
```

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "8f3c...uuid", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z" },
  "role": "MEMBER",
  "alreadyMember": false
}
```

- 이미 멤버면 `200`, `alreadyMember: true`. 멱등이다.
- 방 인원이 20명이면 `ROOM_FULL`.
- 입장 후 FE는 `?invite=` 대신 `/map?room={roomId}`로 URL을 바꾼다. 새로고침해도 멤버십으로 다시 들어온다.

### 방 메타·참여자

`GET /api/v1/map-rooms/{roomId}` (멤버만)

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "...", "status": "ACTIVE", "expiresAt": "...", "role": "MEMBER" },
  "members": [
    { "memberId": "uuid", "displayName": "승현", "role": "OWNER", "joinedAt": "...", "online": true }
  ]
}
```

- `online`은 현재 인스턴스 메모리 기준이다(영속 아님). 다중 인스턴스 전환 전까지는 정확하다.

### 스냅샷 (WS가 끊겼을 때 복구용)

`GET /api/v1/map-rooms/{roomId}/snapshot` (멤버만)

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "lastSeq": 42,
  "pins": [
    {
      "pinId": "uuid", "placeId": "p-okcheon-hanok", "placeName": "옥천성당",
      "lat": 36.3, "lng": 127.7, "addedBy": "uuid", "addedAt": "...", "order": 3
    }
  ]
}
```

- `pins`는 활성 핀만 `order`(= `added_seq`) 오름차순. 선은 이 순서로 그린다.

### 핀 명령 (REST 대체 경로)

WS가 기본 경로다. 같은 서버 명령을 REST로도 쓸 수 있다. WS가 끊긴 상태에서 재시도할 때 쓴다.

- `POST /api/v1/map-rooms/{roomId}/pins`
  - 요청 `{ "opId": "uuid", "placeId": "p-okcheon-hanok" }`
  - 응답 `201` `{ "schemaVersion": "1.2", "seq": 43, "pin": { ...위 pins 항목 } }`
  - 같은 `opId` 재요청은 `200`과 원래 결과를 돌려준다(멱등).
- `DELETE /api/v1/map-rooms/{roomId}/pins/{pinId}?opId=uuid`
  - 응답 `200` `{ "schemaVersion": "1.2", "seq": 44, "pinId": "uuid" }`

### 내 방 목록

`GET /api/v1/map-rooms/mine` — 내가 멤버인 `ACTIVE` 방 목록(최근 입장 순). FE가 "마지막 방으로 복귀"에 쓴다.

## WebSocket

### 연결

`wss://api.onmaru.site/api/v1/map-rooms/{roomId}/ws?lastSeq={n}`

- `lastSeq`는 선택이다. 생략하면 전체 스냅샷을 받는다. 있으면 그 이후 이벤트를 재생받는다.
- 핸드셰이크 검사(순서대로, 실패하면 업그레이드하지 않고 HTTP 오류로 거절)
  1. Origin이 `CorsOriginPolicy`에 있는가 → 아니면 `403`
  2. 세션 쿠키가 유효한가 → 아니면 `401`
  3. 방이 있는가 → 없으면 `404 ROOM_NOT_FOUND`
  4. 방이 `ACTIVE`이고 만료 전인가 → 아니면 `410`
  5. 이 회원이 멤버인가 → 아니면 `403` (초대로 먼저 입장해야 함)
  6. 같은 회원의 동시 연결이 3개 이하인가 → 넘으면 가장 오래된 연결을 끊는다(`end_reason = 'SUPERSEDED'`로 기록)

### 봉투(모든 메시지 공통)

```json
{ "v": 1, "type": "PIN_ADD", "opId": "uuid", "payload": { } }
```

- `v`: 프로토콜 버전. 현재 `1`.
- `type`: 메시지 종류.
- `opId`: 클라이언트 명령에만 있다. 서버 ACK와 짝을 이룬다.
- 최대 크기 4KB. 초과하면 `ERROR` 후 종료(`4000`).

### 클라이언트 → 서버

| type | payload | 설명 |
|---|---|---|
| `PIN_ADD` | `{ "placeId": "p-..." }` | 핀 추가. 이름·좌표는 서버가 결정 |
| `PIN_REMOVE` | `{ "pinId": "uuid" }` | 핀 제거. 누구나 가능, `removed_by` 기록 |
| `CURSOR` | `{ "lat": 36.3, "lng": 127.7 }` 또는 `{ "lat": null, "lng": null }` | 커서. 최대 20Hz 전송, 서버가 50~100ms로 병합 |
| `RESUME` | `{ "lastSeq": 42 }` | 연결 후 재생 요청 (쿼리 파라미터 대신 쓸 수 있음) |

### 서버 → 클라이언트

| type | 시점 | payload 요지 |
|---|---|---|
| `WELCOME` | 연결 직후 | `me`, `room`, `members`, `pins`(활성, order 순), `lastSeq` |
| `EVENT` | 방 상태가 바뀔 때 (모든 멤버에게, 발신자 포함) | `seq`, `eventType`(`PIN_ADDED`/`PIN_REMOVED`/`MEMBER_JOINED`/`ROOM_CLOSED`), `actor`, `pin?` |
| `ACK` | 자신의 명령이 반영됨 | `opId`, `seq`, `pin?` |
| `ERROR` | 명령 거절 또는 프로토콜 오류 | `opId?`, `code`, `message` |
| `CURSORS` | 커서 묶음 (약 100ms마다, 변경된 것만) | `[{ "memberId", "displayName", "lat", "lng" }]` 또는 `lat: null`은 퇴장 |
| `PRESENCE` | 접속·퇴장 (영속 안 함) | `memberId`, `online` |
| `RESYNC_REQUIRED` | 재생 범위를 벗어남 | 클라이언트는 `GET .../snapshot`으로 재동기화 |

- `EVENT`의 `seq`는 방 안에서 1씩 증가한다. 클라이언트는 `seq`가 연속되지 않으면 재연결해 재생을 받는다.
- `ACK`와 `EVENT`는 같은 `seq`를 가진다. 발신자는 `ACK`를 받았으면 자기 낙관적 상태를 확정한다. 같은 변경을 `EVENT`로 또 받으면 `seq`로 중복을 무시한다.

### 명령 처리 규칙

1. 서버는 방별 단일 처리 큐에서 명령을 하나씩 처리한다.
2. `opId`가 이미 이 방에서 처리됐으면 저장된 결과로 `ACK`를 다시 보낸다(멱등).
3. 검증 실패는 `ERROR`로 응답하고 상태를 바꾸지 않는다.
4. 성공하면 DB 트랜잭션(이벤트 + 핀 + 방의 `last_event_seq`)을 커밋한 **뒤에** 메모리 상태를 갱신하고 `EVENT`를 방 전체에 보낸다.
5. DB 실패면 `ERROR`로 응답하고 메모리는 바꾸지 않는다.

### 재연결

- 클라이언트는 연결이 끊기면 지수 백오프(1s, 2s, 4s … 최대 30s)로 재연결하고, 마지막으로 받은 `seq`를 `lastSeq`로 보낸다.
- 서버는 `lastSeq` 이후 이벤트를 순서대로 재생한다. 재생 범위가 방 생애 전체(72시간) 이벤트 보관 범위 안이면 재생, 아니면 `RESYNC_REQUIRED`.
- 재생 중 도착한 새 `EVENT`는 재생이 끝난 뒤 순서대로 붙인다.

### 종료 코드

| code | 의미 | 클라이언트 동작 |
|---|---|---|
| `4000` | 봉투 또는 메시지 형식 오류 | 재연결하지 않음, 버그로 기록 |
| `4001` | 세션 만료 | 재로그인 안내 |
| `4003` | 멤버 아님 또는 Origin 거절 | 재연결하지 않음 |
| `4004` | 방 없음 | 방 선택 화면으로 |
| `4010` | 방 만료·종료 | 만료 안내, 재연결하지 않음 |
| `4029` | 느린 클라이언트로 판단 | 즉시 재연결 (`lastSeq`로 복구) |
| `4030` | 서버 재시작 또는 배포 | 재연결 |

### 서버 측 제한 (기본값, 설정으로 조정)

| 항목 | 값 |
|---|---|
| 방당 멤버 | 20 |
| 방당 활성 핀 | 100 |
| 방당 활성 초대 | 5 |
| 회원당 동시 WS 연결 (방 단위) | 3 |
| 커서 입력 | 20Hz 이하, 서버가 병합 |
| 발신 큐 상한 | 256 메시지, 넘치면 `CURSORS`는 버리고 `EVENT`가 쌓이면 `4029` |
| 하트비트 | WS ping 25초, pong 60초 없으면 종료 (`HEARTBEAT_TIMEOUT`) |
| 방 만료 | 생성 시각 + 72시간 |
