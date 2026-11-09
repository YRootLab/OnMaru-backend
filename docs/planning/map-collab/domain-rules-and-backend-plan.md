# 지도 공동 편집: 도메인 규칙과 백엔드 작업 계획

2026-11-09 기준. 계약 상세는 [map-collab-api.md](../../contracts/map-collab-api.md), 저장 결정은 [ADR-0020](../../decisions/0020-map-collab-room-state-in-memory.md), 스키마는 `V058__map_collab_schema.sql`을 본다.

## 0. 확정한 기본값

사용자 승인 전이지만 아래 기본값으로 설계를 진행한다. 바뀌면 이 표를 먼저 고친다.

| # | 항목 | 기본값 | 근거 |
|---|---|---|---|
| D1 | 같은 방 같은 장소 활성 핀 | 1개만 허용 (`PIN_ALREADY_EXISTS`) | 선이 같은 장소를 두 번 잇는 것을 막음. FE의 사용자별 판단을 방 단위로 바꿔야 함 |
| D2 | 게스트 참여 | 불허 (로그인 회원만) | #777 요구. `identity_guests`는 나중에 연결 가능 |
| D3 | 재핀 순서 | 맨 끝으로 간다 | 이벤트 순서와 `added_seq`의 단순성 유지 |
| D4 | 핀 제거 권한 | 멤버 누구나 제거 가능, `removed_by` 기록 | FE 현재 동작과 같음. 협업 목적에 맞음 |
| D5 | 회원 탈퇴 | 탈퇴 전 활성 멤버십·핀을 정리하는 절차 필요. `added_by`/`removed_by`는 탈퇴 시 익명화 | FK가 탈퇴를 막으므로 명시 절차가 필요 |
| D6 | 방 수명 | 생성 후 72시간 고정 (활동과 무관) | FE 안내 문구("72시간 유지")와 일치 |
| D7 | 초대 링크 | 원문 토큰을 URL에 담고 DB에는 해시만 저장. 활성 5개 상한 | 방 ID를 입장 권한으로 쓰지 않음 |
| D8 | 방 인원 | 20명 (멤버십 기준) | 메모리·브로드캐스트 상한 |
| D9 | 핀 장소 정보 | 서버가 카탈로그에서 이름·좌표를 결정, 클라이언트 값은 무시 | 위조 방지 |
| D10 | 탈퇴·방 종료 시 이벤트 | `ROOM_CLOSED`는 시스템 이벤트(`actor` NULL) | 만료 스위퍼가 기록 |

## 1. 도메인 규칙

### 1.1 방 생명주기

```
ACTIVE ──(expires_at 경과, 스위퍼)──► CLOSED (closed_at, closed_by = NULL)
ACTIVE ──(관리자 종료, 추후)──────────► CLOSED
```

- `ACTIVE`인 방만 입장·명령·WS 연결을 받는다.
- 만료 판단은 `expires_at <= now()` 기준이다. 스위퍼가 주기적으로 `CLOSED`로 바꾸고, 연결된 세션에는 `4010`을 보낸다.
- 만료 직전 상태에서 명령이 들어오면 `ROOM_EXPIRED`로 거절한다. 시계 차이를 줄이려고 서버 시간만 기준으로 한다.
- `CLOSED` 방의 이벤트·핀·세션 이력은 보관한다. 물리 삭제 정책은 별도 보존 정책 문서에서 정한다.

### 1.2 초대 링크

- 발급: 방 생성 시 1개, 이후 멤버가 추가 발급 가능. 활성 상한 5개.
- 만료: `min(발급 시각 + 72시간, 방 expires_at)`.
- 철회: 발급자 또는 OWNER만 가능. `revoked_at` 기록.
- 사용: 토큰 해시로 조회해 유효하면 멤버십을 만든다. 초대 자체는 여러 명이 재사용할 수 있다(사용 횟수 제한 없음).
- 토큰은 추측 불가능한 난수(최소 256비트)다. 조회 실패는 존재 여부를 드러내지 않도록 `INVITE_NOT_FOUND` 하나로 응답한다.

### 1.3 멤버십과 권한

- 멤버십은 초대로만 생긴다. 한 번 생긴 멤버십은 이 설계에서 해제되지 않는다(탈퇴 제외).
- 권한 매트릭스

| 행위 | 멤버 | OWNER | 비멤버 |
|---|---|---|---|
| 방 메타·참여자 조회 | ✔ | ✔ | ✘ |
| 초대 발급 | ✔ | ✔ | ✘ |
| 초대 철회 | 본인이 발급한 것만 | ✔ | ✘ |
| 핀 추가·제거 | ✔ | ✔ | ✘ |
| WS 연결 | ✔ | ✔ | ✘ |

- 방 생성자가 OWNER다. OWNER 이양은 이 범위에서 다루지 않는다.

### 1.4 핀

- 추가: `placeId`가 카탈로그에 있어야 한다(`PLACE_NOT_FOUND`). 같은 방 활성 핀에 같은 장소가 있으면 거절(D1). 활성 핀 상한 100개.
- 제거: `pinId`가 활성 상태여야 한다. 이미 제거됐으면 `PIN_NOT_FOUND`.
- 순서: `added_seq`는 `PIN_ADDED` 이벤트의 seq다. 활성 핀을 이 값으로 정렬하면 선의 순서가 된다.
- 장소 이름·좌표는 추가 시점 스냅샷이다. 카탈로그가 바뀌어도 방의 핀은 바뀌지 않는다.

### 1.5 커서와 presence

- 저장하지 않는다. 메모리에만 있고 연결이 끊기면 사라진다.
- 좌표 범위를 벗어나면 버린다. `null`은 "지도 밖으로 나감"이다.
- 방 안에서만 전달한다. 다른 방으로는 새지 않는다.

### 1.6 이벤트와 멱등성

- 모든 방 변경(핀, 멤버 입장, 방 종료)은 `map_room_events`에 한 행을 남긴다. `seq`는 방 안에서 1씩 증가한다.
- `opId`(클라이언트 UUID)는 `client_op_id`로 저장하고 방 안에서 유일하다. 같은 `opId` 재요청은 원래 결과를 돌려준다.
- 같은 `opId`인데 내용이 다르면 `OP_CONFLICT`.
- 거절된 명령은 이벤트를 남기지 않는다. (감사 로그가 필요하면 별도 로그로 남긴다.)

### 1.7 신원과 표시 이름

- 회원 ID는 `onmaru.identity_members.id`(uuid)다.
- 표시 이름은 현재 `GET /api/v1/members/me`가 쓰는 프로필 값과 같아야 한다. 이름을 얻는 방법은 identity 모듈이 제공하는 조회 포트로 한다.
- 클라이언트가 보낸 이름은 신뢰하지 않는다. 연결 핸드셰이크 시점에 서버가 결정한다.
- FE의 `sessionIdRef`(익명 UUID)는 폐기한다. 식별자는 회원 ID 하나다.

## 2. 상한과 실패 처리

| 상황 | 처리 |
|---|---|
| 방 인원 초과 | 입장 `ROOM_FULL` |
| 활성 핀 초과 | 추가 `PIN_LIMIT_EXCEEDED` |
| 초대 5개 초과 | `INVITE_LIMIT_EXCEEDED` |
| 같은 회원 WS 4개째 | 가장 오래된 연결 `SUPERSEDED` 종료 |
| 발신 큐 가득 참 | 커서 버림, 이벤트 쌓이면 `4029` 종료 후 재연결 |
| DB 쓰기 실패 | 메모리 불변, `ERROR`(`INTERNAL_ERROR`) |
| 재생 범위 밖 | `RESYNC_REQUIRED` → 스냅샷 |
| 서버 재시작 | 연결 `4030` 종료 → 재연결 → 스냅샷/재생 |
| 세션 만료 | `4001` 종료 |

## 3. 모듈과 구조 (ADR-0003 준수)

```
modules/map-collab/                      # 순수 core (Spring/JPA/HTTP 의존 없음)
  domain/        Room, Invite, Membership, Pin, RoomEvent, 규칙·상한
  application/   CreateRoom, IssueInvite, JoinByInvite, AddPin, RemovePin, GetSnapshot 등 유스케이스
  port/          RoomRepository, RoomEventLog, PlaceCatalogPort(consumer-owned), MemberProfilePort, Clock, TokenHasher
  runtime/       RoomActor(방별 단일 큐), RoomRegistry(인터페이스), RoomEventPublisher(인터페이스)

apps/spring-api/                         # 바깥 계층
  web/mapcollab/     REST 컨트롤러, DTO
  web/mapcollab/ws/  WebSocket 핸들러, 핸드셰이크 인터셉터(Origin·세션·멤버십), 세션 레지스트리
  persistence/mapcollab/   JPA/JDBC 어댑터 (V058 테이블)
  adapter/catalog    PlaceCatalogPort 구현 (catalog 모듈 공개 API 사용)
  adapter/identity   MemberProfilePort 구현
```

- `RoomRegistry`는 "이 방의 액터가 어디 있나"를 답하는 인터페이스다. 지금 구현은 메모리 맵 하나다. 다중 인스턴스 시 구현을 바꾼다.
- `RoomEventPublisher`는 이벤트를 방 안 구독자에게 전달하는 인터페이스다. 지금은 세션 레지스트리로 직접 쓴다. 나중에 Redis Streams나 Kafka 구현을 추가한다.
- 현재 `presence` 패키지(`apps/spring-api/.../web/presence`)는 SSE 온기 기능이라 이 기능과 섞지 않는다.

## 4. BE 작업 분해

전제 작업
- **P0. #781 해결**: `/api/v1/members/me` 401 원인을 고친다. 공동 편집 인증 경로가 여기에 의존한다. 이 작업이 끝나기 전에는 FE 전환을 시작해도 검증할 수 없다.

Phase 1: 데이터와 도메인 (기반)
- T1. V058 마이그레이션 확정, 레지스트리 등록 (repo 도구로 sha256 계산), 스키마 통합 테스트 (Testcontainers PostgreSQL)
- T2. 도메인 모델과 규칙 구현 (상한, 상태 전이, 권한 매트릭스), 순수 단위 테스트
- T3. 초대 토큰 발급·해시·검증 (`TokenHasher`), 만료 계산

Phase 2: 명령 처리 (핵심)
- T4. `RoomActor`: 방별 단일 큐, 명령 처리, `opId` 멱등, DB 트랜잭션 후 메모리 반영
- T5. 재시작 복구: 활성 핀과 `last_event_seq`를 DB에서 로드
- T6. 유스케이스: 방 생성, 초대 발급·철회, 입장, 핀 추가·제거, 스냅샷 조회

Phase 3: REST와 WS
- T7. REST 컨트롤러와 공통 오류 응답 (`schemaVersion: "1.2"`, 기존 오류 형식)
- T8. WS 핸드셰이크 검사 (Origin → 세션 → 방 → 상태 → 멤버십 → 동시 연결 수)
- T9. WS 메시지 처리: 봉투 검증, `CURSOR` 병합(50–100ms), `ACK`/`EVENT`/`WELCOME`, `RESUME` 재생
- T10. 발신 큐 상한과 `4029` 종료, 하트비트 25/60초
- T11. 스위퍼: 만료 방 종료, 연결 `4010` 통지

Phase 4: 운영 준비
- T12. 메트릭: 활성 방 수, 연결 수, 명령 지연, DB 트랜잭션 시간, 드롭 수, `4029` 횟수
- T13. 로그: `roomId`, `memberId`, `opId`, `seq`를 구조화 로그로. 좌표·토큰은 기록하지 않음
- T14. nginx WS 설정 (`Upgrade`, `Connection`, 읽기 타임아웃), 배포 중 연결 종료 동작 확인
- T15. 회원 탈퇴 정리 절차 (D5) 구현 및 기존 탈퇴 흐름과 연결

Phase 5: 계약·FE 연동
- T16. `map-collab-api.md`와 실제 응답을 맞추는 계약 테스트 (JSON Schema 검증)
- T17. 프론트엔드 연동 결과를 명세와 대조해 확인 ([API 명세](../../contracts/map-collab-api-spec.md))

각 작업의 완료 기준은 "테스트가 통과하고, 계약 문서와 응답이 일치한다"이다.

## 5. 테스트 계획

| 레벨 | 대상 | 핵심 케이스 |
|---|---|---|
| 단위 | 도메인 규칙 | 상한 경계값(20/100/5), 상태 전이, 권한 매트릭스 |
| 단위 | RoomActor | 같은 `opId` 재전송, DB 실패 시 메모리 불변, 순서 보장 |
| 통합 | DB 제약 | V058 제약 전부 (기존 smoke 시나리오 포함) |
| 통합 | REST | 인증·CSRF·Origin·오류 코드 전부 |
| 통합 | WS | 핸드셰이크 거절 각 단계, `WELCOME`, `ACK`/`EVENT` 순서, `RESUME` 재생, `RESYNC_REQUIRED` |
| 장애 | 재시작 | 활성 핀 복구, seq 단조 증가, 재연결 후 스냅샷 일치 |
| 부하 | 방 20명 × 커서 20Hz | CPU, 발신 큐 드롭 비율, 핀 지연 p95 |
| 보안 | Origin, 토큰 | 허용되지 않은 Origin 거절, 토큰 원문이 DB·로그에 없음, 비멤버 접근 차단 |

## 6. 관측 지표 (초기)

- `map_collab_rooms_active` (게이지)
- `map_collab_ws_connections` (게이지, 방 단위 라벨은 쓰지 않음 — 카디널리티 주의)
- `map_collab_command_duration_seconds` (히스토그램, `type` 라벨)
- `map_collab_command_rejected_total` (카운터, `code` 라벨)
- `map_collab_ws_closed_total` (카운터, `close_code` 라벨)
- `map_collab_outbound_dropped_total` (카운터, `kind`=cursor|event 라벨)

## 7. 개선 백로그 (지금은 하지 않음)

- 커서 병합 주기 튜닝, 방 단위 스레드 풀 크기 조정
- 방 상태 스냅샷 캐시 (재접속 폭주 대비)
- 이벤트 보관 기간 축소, 오래된 `CLOSED` 방 아카이브
- 여러 인스턴스 라우팅: nginx 방 URI 해시 → 필요 시 Redis Streams
- Debezium CDC로 `map_room_events` 발행 (분석·알림)
- 코스 저장 (핀 목록 → 여정 변환)

## 8. 남은 확인 사항

1. D1~D10 확정 여부 (특히 D1 — FE 중복 핀 동작 변경 필요)
2. 프로필 조회 포트: `members/me`가 쓰는 `displayName` 필드의 정확한 출처
3. 방 메타에 이름을 붙일지 (현재 설계는 이름 없음)
4. 모듈 이름 `map-collab` 확정 여부
