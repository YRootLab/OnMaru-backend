# 지도 공동 편집 API 명세

- 대상: 프론트엔드
- 상태: 설계 확정 전. 아래 기본값(D1~D10)을 전제로 작성했고, 구현은 아직 진행 전입니다.
- 명세 버전: 1.0.0-draft
- 정식 계약 파일: [OpenAPI (REST)](openapi/map-collab.openapi.yaml), [JSON Schema (WebSocket 메시지)](schemas/map-collab-ws-message.schema.json)
- 예시 파일: [REST 예시 응답](fixtures/map-collab/), [WebSocket 예시 메시지](fixtures/map-collab/ws/), [거절되는 WebSocket 메시지](fixtures/map-collab/ws/invalid/)

이 문서는 백엔드가 제공하는 동작을 설명합니다. 정식 계약 파일과 내용이 다르면 정식 계약 파일이 기준입니다.

---

## 1. 개요

지도 화면에서 로그인한 회원들이 초대 링크로 같은 지도 방에 들어와 장소를 핀으로 찍고, 찍은 순서대로 이어진 선을 함께 볼 수 있도록 다음 기능을 제공합니다.

- 방 생성과 초대 링크 발급
- 초대 링크로 입장, 멤버 목록 조회
- 장소 핀 추가와 제거, 활성 핀의 순서 조회
- 실시간 채널(WebSocket)을 통한 핀 변경 전파, 다른 참여자 커서, 접속 상태

REST는 방과 초대, 스냅샷 조회에 쓰이고, 핀 변경과 커서는 WebSocket을 기본 경로로 제공합니다. 핀 추가와 제거는 WebSocket이 끊겼을 때 쓰는 REST 경로도 같은 결과로 제공합니다.

---

## 2. 공통 규칙

| 항목 | 내용 |
|---|---|
| 기본 경로 | `/api/v1` |
| 형식 | HTTPS, JSON (UTF-8) |
| 인증 | 로그인 세션 쿠키 `__Host-onmaru-session`. 모든 요청에 필요합니다. |
| CSRF | POST, DELETE 요청에는 `X-CSRF-TOKEN` 헤더가 필요하며, 값은 `__Host-onmaru-csrf` 쿠키와 같아야 합니다. |
| 버전 표시 | 모든 응답 본문의 `schemaVersion`은 `"1.2"`입니다. |
| 시간 | ISO-8601 UTC 문자열 (예: `2026-11-12T03:00:00Z`) |
| 식별자 | UUID 문자열 |
| 장소 식별자 | 카탈로그 공개 ID. `p-` 로 시작하는 소문자와 숫자, 하이픈 조합입니다. (예: `p-okcheon-hanok`) |
| 오류 형식 | `schemaVersion`, `code`, `message`, `requestId`, `details`로 구성됩니다. 사용자에게 보여줄 문구는 `code`에 따라 정해집니다. `message`는 진단용입니다. |

### 2.1 장소 정보의 출처

핀에 담기는 장소 이름과 좌표는 서버가 카탈로그에서 결정합니다. 클라이언트가 보낸 이름이나 좌표는 사용하지 않습니다. 핀 추가 요청에는 `placeId`만 담깁니다.

---

## 3. 리소스 모델

**Room (방)**
- `id`, `status`(`ACTIVE` 또는 `CLOSED`), `expiresAt`
- 생성 시각부터 72시간 뒤에 만료됩니다. 만료되면 `CLOSED`가 되고, 이후 명령은 `ROOM_EXPIRED`로 거절됩니다.

**Member (멤버)**
- `memberId`, `displayName`, `role`(`OWNER` 또는 `MEMBER`), `joinedAt`, `online`
- 방을 만든 회원이 `OWNER`입니다. 초대로 들어온 회원은 `MEMBER`입니다.
- `online`은 현재 접속 여부이며, 서버 메모리 기준입니다.

**Invite (초대 링크)**
- 원문 토큰은 생성 또는 발급 응답에서 한 번만 제공됩니다. 이후에는 다시 조회할 수 없습니다.
- 토큰은 43~64자의 base64url 문자열입니다.
- `shareUrl` 형식: `https://onmaru.site/map?invite={token}`
- 유효 기간: `min(발급 시각 + 72시간, 방의 expiresAt)`

**Pin (핀)**
- `pinId`(클라이언트가 만든 UUID), `placeId`, `placeName`, `lat`, `lng`, `addedBy`, `addedAt`, `order`
- `order`는 핀이 찍힌 순서입니다. 선은 `order` 오름차순으로 이어집니다.
- 제거된 핀은 활성 목록에 나오지 않습니다.

---

## 4. REST 엔드포인트

### 4.1 방 생성

`POST /api/v1/map-rooms` (로그인, CSRF 필요)

요청 본문은 없습니다.

응답 `201`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "8f3c2a10-6b1d-4c7e-9a52-3d1f0e6b7c01", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z", "role": "OWNER" },
  "invite": {
    "token": "발급된 토큰 원문 (한 번만 제공)",
    "shareUrl": "https://onmaru.site/map?invite=발급된 토큰 원문",
    "expiresAt": "2026-11-12T03:00:00Z"
  }
}
```

- 요청한 회원은 `OWNER`로 방에 들어가 있는 상태가 됩니다.

### 4.2 초대 링크 추가 발급

`POST /api/v1/map-rooms/{roomId}/invites` (멤버, CSRF 필요)

응답 `201`
```json
{
  "schemaVersion": "1.2",
  "invite": { "token": "...", "shareUrl": "https://onmaru.site/map?invite=...", "expiresAt": "2026-11-12T03:00:00Z" }
}
```

- 방당 활성 초대는 최대 5개입니다. 초과하면 `INVITE_LIMIT_EXCEEDED`를 응답합니다.
- 이전에 발급한 링크는 만료 전까지 계속 유효합니다.

### 4.3 초대 링크 철회

`DELETE /api/v1/map-rooms/{roomId}/invites/{inviteId}` (발급자 또는 OWNER, CSRF 필요)

- 응답 `204`(본문 없음)
- 이미 철회된 초대를 다시 철회해도 `204`를 응답합니다.

### 4.4 초대로 입장

`POST /api/v1/map-rooms/joins` (로그인, CSRF 필요)

요청
```json
{ "token": "초대 토큰 원문" }
```

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "8f3c2a10-6b1d-4c7e-9a52-3d1f0e6b7c01", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z" },
  "role": "MEMBER",
  "alreadyMember": false
}
```

- 이미 멤버인 회원이 요청하면 `alreadyMember: true`와 함께 `200`을 응답합니다. 같은 요청을 여러 번 보내도 결과는 같습니다.
- 토큰이 없거나 만료, 철회되었으면 `INVITE_NOT_FOUND`(404)를 응답합니다. 이유는 구분하지 않습니다.
- 방 인원이 20명이면 `ROOM_FULL`(409)을 응답합니다.

### 4.5 방 상세와 멤버 목록

`GET /api/v1/map-rooms/{roomId}` (멤버만)

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "room": { "id": "8f3c2a10-6b1d-4c7e-9a52-3d1f0e6b7c01", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z", "role": "MEMBER" },
  "members": [
    { "memberId": "0b9c8d7e-6f5a-4b3c-9d2e-1f0a9b8c7d6e", "displayName": "승현", "role": "OWNER", "joinedAt": "2026-11-09T03:00:00Z", "online": true },
    { "memberId": "5e2d1c0b-9a8f-4e7d-8c6b-5a4f3e2d1c0b", "displayName": "민지", "role": "MEMBER", "joinedAt": "2026-11-09T03:05:00Z", "online": false }
  ]
}
```

- 멤버가 아니면 `FORBIDDEN`(403)을 응답합니다.
- 방이 없으면 `ROOM_NOT_FOUND`(404)를 응답합니다.

### 4.6 스냅샷

`GET /api/v1/map-rooms/{roomId}/snapshot` (멤버만)

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "lastSeq": 42,
  "pins": [
    { "pinId": "7a1c2b3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d", "placeId": "p-okcheon-hanok", "placeName": "옥천성당", "lat": 36.3, "lng": 127.7, "addedBy": "0b9c8d7e-6f5a-4b3c-9d2e-1f0a9b8c7d6e", "addedAt": "2026-11-09T03:00:00Z", "order": 1 },
    { "pinId": "1f2e3d4c-5b6a-4978-8a9b-0c1d2e3f4a5b", "placeId": "p-gyeongju-bulguksa", "placeName": "불국사", "lat": 35.79, "lng": 129.33, "addedBy": "5e2d1c0b-9a8f-4e7d-8c6b-5a4f3e2d1c0b", "addedAt": "2026-11-09T03:05:00Z", "order": 2 }
  ]
}
```

- `pins`는 활성 핀만 `order` 오름차순으로 제공합니다.
- `lastSeq`는 이 스냅샷에 반영된 마지막 이벤트 번호입니다. 이후 변경은 WebSocket을 `lastSeq`와 함께 연결해서 받을 수 있습니다.
- 방이 만료되었으면 `ROOM_EXPIRED`(410)를 응답합니다.

### 4.7 핀 추가 (REST 경로)

`POST /api/v1/map-rooms/{roomId}/pins` (멤버, CSRF 필요)

요청
```json
{ "opId": "d1a2b3c4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", "placeId": "p-gyeongju-bulguksa" }
```

응답
- `201`: 새로 추가되었습니다.
  ```json
  { "schemaVersion": "1.2", "seq": 43, "pin": { "pinId": "...", "placeId": "p-gyeongju-bulguksa", "placeName": "불국사", "lat": 35.79, "lng": 129.33, "addedBy": "...", "addedAt": "...", "order": 2 } }
  ```
- `200`: 같은 `opId`로 다시 요청하면 처음 결과를 그대로 응답합니다.
- `409` `PIN_ALREADY_EXISTS`: 같은 방에 같은 장소의 활성 핀이 이미 있습니다. `details.existingPin`에 기존 핀이 담겨 옵니다.
- `409` `PIN_LIMIT_EXCEEDED`: 활성 핀이 100개에 도달했습니다.
- `409` `OP_CONFLICT`: 같은 `opId`에 다른 내용이 왔습니다.
- `404` `PLACE_NOT_FOUND`: 카탈로그에 없는 장소입니다.

`opId`는 방 안에서 유일해야 하며, 재시도할 때 같은 값을 쓰면 중복 반영되지 않습니다.

### 4.8 핀 제거 (REST 경로)

`DELETE /api/v1/map-rooms/{roomId}/pins/{pinId}?opId={uuid}` (멤버, CSRF 필요)

응답 `200`
```json
{ "schemaVersion": "1.2", "seq": 44, "pinId": "7a1c2b3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d" }
```

- 멤버 누구나 제거할 수 있습니다. 제거한 회원은 서버에 기록됩니다.
- 이미 제거된 핀이면 `PIN_NOT_FOUND`(404)를 응답합니다.
- 같은 `opId`로 다시 요청하면 `200`과 처음 결과를 응답합니다.

### 4.9 내 방 목록

`GET /api/v1/map-rooms/mine` (로그인)

응답 `200`
```json
{
  "schemaVersion": "1.2",
  "rooms": [ { "id": "8f3c2a10-6b1d-4c7e-9a52-3d1f0e6b7c01", "status": "ACTIVE", "expiresAt": "2026-11-12T03:00:00Z", "role": "OWNER", "joinedAt": "2026-11-09T03:00:00Z" } ]
}
```

- 최근 입장 순으로 정렬됩니다. 만료되었거나 종료된 방은 포함되지 않습니다.

---

## 5. 실시간 채널 (WebSocket)

### 5.1 연결

```
wss://api.onmaru.site/api/v1/map-rooms/{roomId}/ws?lastSeq={n}
```

- `lastSeq`는 선택 항목입니다. 생략하면 연결 직후 `WELCOME`으로 전체 상태를 받습니다. 값을 주면 그 번호 이후의 이벤트를 순서대로 재생합니다.

### 5.2 핸드셰이크 검사

업그레이드 전에 아래 순서로 검사하며, 실패하면 HTTP 오류로 거절합니다.

| 순서 | 검사 | 실패 응답 |
|---|---|---|
| 1 | Origin이 허용 목록에 있음 | 403 |
| 2 | 세션 쿠키가 유효함 | 401 |
| 3 | 방이 있음 | 404 `ROOM_NOT_FOUND` |
| 4 | 방이 활성이고 만료 전임 | 410 |
| 5 | 회원이 멤버임 | 403 |
| 6 | 같은 회원의 동시 연결이 3개 이하임 | 넘으면 가장 오래된 연결이 종료됩니다 |

WebSocket 핸드셰이크는 CSRF 헤더 대신 Origin으로 검사합니다.

### 5.3 메시지 봉투

모든 메시지는 같은 봉투를 씁니다.

```json
{ "v": 1, "type": "PIN_ADD", "opId": "d1a2b3c4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", "payload": { "placeId": "p-okcheon-hanok" } }
```

- `v`: 프로토콜 버전. 현재 `1`입니다.
- `type`: 메시지 종류
- `opId`: 클라이언트가 보내는 명령에만 있습니다. 서버의 `ACK`나 `ERROR`에 같은 값이 돌아옵니다.
- 메시지 최대 크기는 4KB입니다. 넘으면 `ERROR`를 보낸 뒤 `4000`으로 연결을 종료합니다.

### 5.4 클라이언트가 보내는 메시지

| `type` | `payload` | 설명 |
|---|---|---|
| `PIN_ADD` | `{ "placeId": "p-..." }` | 핀 추가. 이름과 좌표는 서버가 정합니다. |
| `PIN_REMOVE` | `{ "pinId": "uuid" }` | 핀 제거 |
| `CURSOR` | `{ "lat": 36.31, "lng": 127.71 }` | 커서 위치. 서버는 초당 최대 20회 입력을 받아 50~100ms 단위로 묶어 전달합니다. |
| `CURSOR` | `{ "lat": null, "lng": null }` | 지도 밖으로 나갔음 |
| `RESUME` | `{ "lastSeq": 42 }` | 재생 요청 (연결 후 보낼 수 있음) |

`PIN_ADD`와 `PIN_REMOVE`는 `opId`가 필수입니다. 빠지면 거절됩니다.

### 5.5 서버가 보내는 메시지

| `type` | 보내는 시점 | 주요 내용 |
|---|---|---|
| `WELCOME` | 연결 직후 | `me`, `room`, `members`, `pins`(활성 핀, `order` 순), `lastSeq` |
| `EVENT` | 방 상태가 바뀔 때 (발신자 포함 모든 멤버) | `seq`, `eventType`, `actor`, `pin`(핀 이벤트) 또는 `pinId`(제거 이벤트) |
| `ACK` | 자신의 명령이 반영됨 | `opId`, `seq`, `pin` 또는 `pinId` |
| `ERROR` | 명령이 거절됨 | `opId`(있으면), `code`, `message`, 중복 핀인 경우 `existingPin` |
| `CURSORS` | 커서가 바뀔 때 (약 100ms 단위) | `cursors` 배열. `lat`이 `null`이면 그 참여자가 지도 밖으로 나간 것입니다. 한 번에 최대 20명입니다. |
| `PRESENCE` | 접속 또는 퇴장 | `memberId`, `online` |
| `RESYNC_REQUIRED` | 재생할 수 없을 때 | `reason`(`OUT_OF_RETENTION` 또는 `GAP`). 이후 상태 복구는 스냅샷 REST를 기준으로 합니다. |

`eventType`은 `PIN_ADDED`, `PIN_REMOVED`, `MEMBER_JOINED`, `ROOM_CLOSED` 중 하나입니다.

`ERROR`의 `code`는 다음 중 하나입니다: `VALIDATION_ERROR`, `PLACE_NOT_FOUND`, `PIN_ALREADY_EXISTS`, `PIN_LIMIT_EXCEEDED`, `PIN_NOT_FOUND`, `OP_CONFLICT`, `ROOM_EXPIRED`, `ROOM_CLOSED`, `RATE_LIMITED`, `INTERNAL_ERROR`

### 5.6 순서와 번호 규칙

- `seq`는 방 안에서 1씩 증가합니다. 서버는 명령을 하나씩 처리하고, 처리 결과마다 번호를 하나 부여합니다.
- 핀의 `order`는 핀을 추가한 이벤트의 `seq`와 같습니다. 선의 순서는 이 값으로 정해집니다.
- 같은 변경은 발신자에게 `ACK`로, 모든 참여자에게 `EVENT`로 전달됩니다. 두 메시지의 `seq`는 같으며, 같은 `seq`의 변경은 한 번만 반영된다고 보면 됩니다.
- 명령을 재전송하면 `opId`로 중복을 막습니다. 같은 `opId`에는 같은 결과가 돌아옵니다.
- 명령이 거절되면 상태는 바뀌지 않으며 `ERROR`만 발신자에게 전달됩니다.

### 5.7 재연결과 재생

- 연결이 끊기면 마지막으로 받은 `seq`를 `lastSeq`로 넘겨 다시 연결할 수 있습니다.
- 서버는 `lastSeq` 이후의 이벤트를 `seq` 순서대로 재생합니다. 재생이 끝나면 현재 상태를 담은 `WELCOME`을 보냅니다.
- 재생 가능한 범위는 방의 수명(72시간)입니다. 범위를 벗어나면 `RESYNC_REQUIRED`를 보냅니다.
- 서버가 재배포되면 연결이 `4030`으로 종료됩니다. 이 경우 재연결하면 같은 방 상태를 받을 수 있습니다.

### 5.8 종료 코드

| 코드 | 의미 |
|---|---|
| 4000 | 메시지 형식 오류 |
| 4001 | 세션 만료 |
| 4003 | 멤버가 아니거나 Origin 거절 |
| 4004 | 방 없음 |
| 4010 | 방 만료 또는 종료 |
| 4029 | 느린 클라이언트로 판단되어 종료 (이벤트 재생으로 복구 가능) |
| 4030 | 서버 재시작 또는 배포 |

하트비트는 서버가 25초마다 ping을 보내고, 60초 동안 pong이 없으면 연결을 종료합니다.

---

## 6. 오류 코드

| `code` | HTTP | 의미 |
|---|---|---|
| `VALIDATION_ERROR` | 400 | 요청 형식 오류 |
| `UNAUTHORIZED` | 401 | 로그인이 필요하거나 세션 만료 |
| `FORBIDDEN` | 403 | 멤버가 아님, CSRF 또는 Origin 검사 실패 |
| `ROOM_NOT_FOUND` | 404 | 방이 없음 |
| `INVITE_NOT_FOUND` | 404 | 초대 토큰이 없거나 만료, 철회됨 |
| `PLACE_NOT_FOUND` | 404 | 카탈로그에 없는 장소 |
| `PIN_NOT_FOUND` | 404 | 핀이 없거나 이미 제거됨 |
| `ROOM_EXPIRED` | 410 | 방 만료 |
| `ROOM_CLOSED` | 410 | 방 종료 |
| `ROOM_FULL` | 409 | 방 인원 20명 초과 |
| `INVITE_LIMIT_EXCEEDED` | 409 | 활성 초대 5개 초과 |
| `PIN_ALREADY_EXISTS` | 409 | 같은 장소의 활성 핀이 이미 있음 (`details.existingPin` 포함) |
| `PIN_LIMIT_EXCEEDED` | 409 | 활성 핀 100개 초과 |
| `OP_CONFLICT` | 409 | 같은 `opId`에 다른 내용이 옴 |
| `RATE_LIMITED` | 429 | 요청 빈도 초과. `Retry-After` 헤더가 함께 옵니다. |
| `INTERNAL_ERROR` | 500 | 서버 내부 오류 |

---

## 7. 동작 규칙 요약

- **핀 중복 (D1)**: 같은 방에서 같은 장소의 활성 핀은 하나만 존재합니다. 제거한 뒤에는 다시 찍을 수 있으며, 새 핀은 선의 맨 끝에 놓입니다 (D3).
- **제거 권한 (D4)**: 멤버 누구나 핀을 제거할 수 있습니다. 제거한 회원은 `removedBy`로 기록됩니다.
- **참여 대상 (D2)**: 로그인한 회원만 참여할 수 있습니다. 게스트는 지원하지 않습니다.
- **방 수명 (D6)**: 생성 시각부터 72시간입니다. 연장되지 않습니다.
- **초대 링크 (D7)**: 원문 토큰은 한 번만 제공되고 서버에는 해시만 남습니다. 활성 초대는 방당 5개입니다.
- **상한 (D8)**: 방당 멤버 20명, 활성 핀 100개, 회원당 방 연결 3개
- **장소 정보 (D9)**: 핀의 이름과 좌표는 서버가 카탈로그에서 가져옵니다.
- **만료 (D10)**: 만료는 시스템이 기록하며, 연결된 참여자에게 `ROOM_CLOSED` 또는 `4010`으로 알립니다.

---

## 8. 이번 버전에서 제공하지 않는 것

- 게스트 참여
- 방 이름 설정
- 방장 이양
- 방 수동 종료
- 핀을 모아 코스로 저장하는 기능
- 경로 최적화나 이동 시간 계산

---

## 9. 확정 대기 항목

아래 항목은 아직 확정되지 않았고, 확정되면 이 문서와 정식 계약 파일을 함께 고칩니다.

- D1: 같은 장소의 활성 핀 중복 금지 (프론트엔드의 버튼 상태 판단 기준과 관련됩니다.)
- D7: 초대 링크 형식 `?invite=` (기존 `?room=` 링크와 관련됩니다.)
- 표시 이름(`displayName`)의 출처: 회원 프로필 조회와 같은 값인지 확인 중입니다.
- 방 이름 부여 여부
- 커서 전송 빈도 20Hz의 적정성

---

## 10. 파일 목록

| 파일 | 설명 |
|---|---|
| [openapi/map-collab.openapi.yaml](openapi/map-collab.openapi.yaml) | REST 정식 계약 |
| [schemas/map-collab-ws-message.schema.json](schemas/map-collab-ws-message.schema.json) | WebSocket 메시지 정식 계약 |
| [fixtures/map-collab/](fixtures/map-collab/) | REST 예시 응답 26개 |
| [fixtures/map-collab/ws/](fixtures/map-collab/ws/) | WebSocket 예시 메시지 13개 |
| [fixtures/map-collab/ws/invalid/](fixtures/map-collab/ws/invalid/) | 거절되어야 하는 WebSocket 메시지 5개 |
| [map-collab-api.md](map-collab-api.md) | 계약의 근거와 제약 설명 |
| [../planning/map-collab/domain-rules-and-backend-plan.md](../planning/map-collab/domain-rules-and-backend-plan.md) | 도메인 규칙과 기본값 D1~D10 |
