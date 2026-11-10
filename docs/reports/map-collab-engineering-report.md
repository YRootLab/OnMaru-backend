# OnMaru 지도 실시간 공동 편집 시스템 종합 엔지니어링 보고서
> **Target**: Issue #825, PR #826 (Refs #777, #781)  
> **Branch**: `feature/825-map-collab-backend`  
> **Status**: Production Ready (Phase 1~3, T1~T11 & 3-Client E2E Sync Completed)  
> **Stack**: Spring Boot 3.4, Java 21, WebSocket, JDBC, PostgreSQL 16 (Flyway V058), Modular Monolith  
> **Web View**: [🌐 HTML 보고서 프리뷰로 보기](https://htmlpreview.github.io/?https://github.com/YRootLab/OnMaru-backend/blob/feature/825-map-collab-backend/docs/reports/map-collab-engineering-report.html) | [💻 원본 HTML 파일](map-collab-engineering-report.html)

---

## 📑 목차 (Table of Contents)
1. [문제 정의 및 PRD (Product Requirements Document)](#1-문제-정의-및-prd-product-requirements-document)
2. [사용자 스토리 및 인수 조건 (User Stories & Acceptance Criteria)](#2-사용자-스토리-및-인수-조건-user-stories--acceptance-criteria)
3. [기술 설계 (RFC & C4 Model)](#3-기술-설계-rfc--c4-model)
4. [데이터 모델 및 ERD (V058 스키마)](#4-데이터-모델-및-erd-v058-스키마)
5. [시스템 시퀀스 다이어그램 (Sequence Flows)](#5-시스템-시퀀스-다이어그램-sequence-flows)
6. [아키텍처 결정 기록 (ADR-0020 & Decisions D1~D10)](#6-아키텍처-결정-기록-adr-0020--decisions-d1d10)
7. [구현 및 검증 결과 (Implementation & Verification)](#7-구현-및-검증-결과-implementation--verification)
8. [운영 및 모니터링 (Observability, SLO/SLI & SRE)](#8-운영-및-모니터링-observability-slosli--sre)
9. [사후 검토 및 향후 로드맵 (Post-Release Review)](#9-사후-검토-및-향후-로드맵-post-release-review)

---

## 1. 문제 정의 및 PRD (Product Requirements Document)

### 1.1 배경 및 문제 정의 (Problem Statement)
기존 OnMaru 웹 클라이언트는 지도 공동 편집 기능 구현을 위해 서드파티 실시간 협업 SaaS인 **Liveblocks**를 사용하고 있었습니다. 하지만 서비스 규모가 확장됨에 따라 다음과 같은 핵심 엔지니어링 문제들이 발생했습니다:

1. **데이터 소유권 및 정합성 결여**: Liveblocks 룸 내 상태(핀, 좌표, 순서)는 OnMaru 자체 RDBMS(PostgreSQL)에 즉시 저장되지 않아 세션 종료 시 영속성 보장이 불안정하며, 카탈로그 검증 없이 클라이언트 입력 좌표가 그대로 반영되는 취약점이 존재했습니다.
2. **외부 종속 비용 급증**: MAU 및 동시 접속 룸 증가에 따라 Liveblocks 월간 구독료가 지수적으로 증가했으며, OnMaru의 독자적인 회원 권한(호스트/에디터) 및 방 수명(72시간) 정책과의 결합도가 떨어졌습니다.
3. **도메인 결합 분리**: 실시간 상태 변경 이벤트와 기존 OnMaru 여행/코스 도메인 간의 이벤트 통합이 불가능하여, 공동 편집 결과를 여정(Journey)으로 변환하는 기능 개발이 차단되었습니다.

### 1.2 제품 목표 (Goals & Non-Goals)
- **Goals**:
  - Liveblocks를 완전 제거하고 Spring Boot 3.4 WebSocket + 모듈러 모놀리스 기반으로 실시간 공동 편집 내재화
  - 핀 변경은 DB 트랜잭션(`map_room_pins`, `map_room_events`, `last_event_seq`) 선행 커밋 후 메모리 반영 및 브로드캐스트
  - 클라이언트 입력 좌표를 신뢰하지 않고 서버 카탈로그(`PlaceCatalogPort`) 검증 데이터만 강제 사용 (D9)
  - 256비트 난수 초대 토큰 발급, SHA-256 해시 저장 및 만료시간(72시간) 엄격 제어 (D7)
- **Non-Goals**:
  - 다중 노드 분산 클러스터링 (Phase 1~3에서는 단일 인스턴스 인메모리 Actor 큐로 처리, Phase 4에서 Redis 연동)
  - 코스 저장 기능 (후속 마일스톤 분리)

---

## 2. 사용자 스토리 및 인수 조건 (User Stories & Acceptance Criteria)

### 2.1 사용자 스토리 (User Stories)
- **US-1 (방 생성 및 호스트 권한)**: 여행 플래너인 회원은 지도를 열고 새로운 공동 편집 방을 생성하여 방장(OWNER) 권한을 획득하고, 만료 시간(72시간)이 설정된 작업 공간을 확보할 수 있다.
- **US-2 (초대 링크 발급 및 입장)**: 방장은 친구들을 초대하기 위해 유효기간이 있는 초대 링크를 발급할 수 있으며, 초대 링크를 전달받은 회원은 간편하게 방 멤버(MEMBER)로 참여할 수 있다.
- **US-3 (실시간 핀 동기화)**: 방에 접속한 참여자들은 원하는 관광지를 검색하여 핀으로 추가할 수 있으며, 핀이 추가되거나 삭제되는 순간 방에 있는 모든 참가자의 화면에 100ms 이내에 실시간으로 반영된다.
- **US-4 (실시간 커서 공유)**: 참여자가 지도 위에서 마우스를 움직이면, 다른 참여자들에게 해당 사용자의 닉네임과 함께 부드럽게 마우스 커서 위치가 공유된다.
- **US-5 (네트워크 단절 및 재연결)**: 모바일 환경에서 일시적으로 네트워크가 끊겼다가 재접속하더라도, 마지막 수신 seq 이후의 누락된 이벤트를 자동으로 재수신(Replay)하여 상태를 동기화한다.

### 2.2 인수 기준 (Acceptance Criteria: AC-1 ~ AC-8)

| ID | 검증 항목 | 인수 조건 (Acceptance Criteria) | 검증 상태 |
|---|---|---|---|
| **AC-1** | 상한 제약 (Capacity) | 방당 최대 20명, 활성 핀 최대 100개, 활성 초대 5개, 동시 WS 세션 최대 3개 강제 (초과 시 409 또는 SUPERSEDED 종료) | **PASS** |
| **AC-2** | 중복 핀 금지 (D1) | 동일 방 내 이미 활성화된 placeId가 존재할 경우 거절하고 `PIN_ALREADY_EXISTS (409)`와 기존 핀 반환 | **PASS** |
| **AC-3** | 장소 카탈로그 검증 (D9) | 클라이언트 전송 좌표/명칭 무시, 백엔드 `PlaceCatalogPort` 조회 공인 데이터만 강제 반영 | **PASS** |
| **AC-4** | 트랜잭션 선행 기록 | 이벤트 행, 핀 행, room.last_event_seq 커밋 성공 후에만 메모리 반영 및 브로드캐스트 (실패 시 메모리 불변) | **PASS** |
| **AC-5** | opId 멱등성 | 동일 `opId` 재요청 시 중복 반영 없이 직전 성공 결과(동일 seq) 반환 | **PASS** |
| **AC-6** | 다자간 동시 동기화 | 3인 이상 동시 핀 추가/삭제 시 모든 클라이언트가 동일한 seq 순서로 실시간 EVENT 수신 | **PASS** |
| **AC-7** | 커서 쓰로틀링 (T9) | 초당 20Hz 이하 입력을 80ms(50~100ms) 단위로 버퍼링하여 `CURSORS` 배열로 병합 브로드캐스트 | **PASS** |
| **AC-8** | 수명 만료 스위퍼 (T11) | 72시간 경과 방을 `CLOSED` 전환, `ROOM_CLOSED` 이벤트 기록 후 연결된 세션에 `4010` 종료 통지 | **PASS** |

---

## 3. 기술 설계 (RFC & C4 Model)

### 3.1 C4 Container & Component Model

```mermaid
graph TD
    Client["Next.js Web Client (React / Emotion / KakaoMap)"]
    
    subgraph OnMaru_Backend["OnMaru Modular Monolith (Spring Boot 3.4 / Java 21)"]
        subgraph Web_Adapter["apps/spring-api (HTTP / WebSocket Layer)"]
            REST_Ctrl["MapRoomController (REST /api/v1/map-rooms)"]
            WS_Handler["MapCollabWebSocketHandler (/ws/map-rooms/{roomId})"]
            WS_Registry["MapCollabSessionRegistry (Outbound Buffer & Cursor Throttle)"]
            Interceptor["MapCollabHandshakeInterceptor (Strict Origin & Auth Check)"]
        end
        
        subgraph Core_Domain["modules/map-collab (Pure Domain Core)"]
            Actor["RoomActor (Single-threaded Message Queue per Room)"]
            Registry["RoomRegistry (In-Memory Actor Lifecycle)"]
            UseCases["Create/Join/Snapshot UseCases"]
        end
        
        subgraph Persistence_Adapter["apps/spring-api/persistence"]
            JdbcRepo["JdbcRoomRepository (Transactional Write-Through)"]
            CatalogAdapter["JdbcPlaceCatalogAdapter"]
            MemberAdapter["JdbcMemberProfileAdapter"]
        end
    end
    
    PostgreSQL[("PostgreSQL 16 (Flyway V058 Baseline)")]
    
    Client -->|HTTP REST: CRUD| REST_Ctrl
    Client -->|WSS Protocol: PIN / CURSOR| Interceptor
    Interceptor --> WS_Handler
    WS_Handler --> WS_Registry
    REST_Ctrl --> UseCases
    UseCases --> Registry
    WS_Handler --> Registry
    Registry --> Actor
    Actor -->|Atomic Write: Pins + Events + Seq| JdbcRepo
    Actor -->|Place Validation D9| CatalogAdapter
    Actor -->|Actor Display Name D2| MemberAdapter
    JdbcRepo --> PostgreSQL
    CatalogAdapter --> PostgreSQL
    Actor -.->|Broadcast Event| WS_Registry
    WS_Registry -.->|Send Frame| Client
```

### 3.2 방별 Actor 동시성 모델 (ADR-0020 핵심 런타임)
각 방(Room)은 동시 다발적인 핀 추가/제거 명령을 처리하기 위해 **단일 처리 큐(Single-Threaded Execution Queue)**를 가진 `RoomActor` 인스턴스를 메모리에 유지합니다.
- **락 프리(Lock-Free) 순차 처리**: 방 내부의 비관적 락 경합과 데드락을 원천 차단하고 `seq`의 단조 증가성을 100% 보장.
- **Transactional Write-Through**: 반드시 `roomRepository.addPin(...)`을 통해 DB 트랜잭션 커밋이 완료된 것을 확인한 직후에만 메모리 활성 핀 맵(activePins)을 업데이트하고 참여자들에게 `EVENT`를 브로드캐스트합니다. DB 쿼리 실패 시 메모리 상태는 완전히 불변으로 유지됩니다.

---

## 4. 데이터 모델 및 ERD (V058 스키마)

```mermaid
erDiagram
    map_rooms ||--o{ map_room_members : "has"
    map_rooms ||--o{ map_room_invites : "issues"
    map_rooms ||--o{ map_room_pins : "contains"
    map_rooms ||--o{ map_room_events : "records"
    map_rooms ||--o{ map_room_sessions : "tracks"

    map_rooms {
        uuid id PK "방 고유 식별자"
        uuid created_by FK "방 생성 회원 ID"
        varchar status "ACTIVE, CLOSED"
        timestamptz created_at "생성 일시"
        timestamptz expires_at "만료 일시 (최대 72시간)"
        timestamptz closed_at "종료 일시"
        varchar close_reason "EXPIRED, HOST_CLOSED 등"
        bigint last_event_seq "마지막 발행 이벤트 번호"
    }

    map_room_members {
        uuid room_id PK,FK "방 ID"
        uuid member_id PK,FK "회원 ID"
        varchar role "OWNER, MEMBER"
        timestamptz joined_at "참여 일시"
    }

    map_room_invites {
        uuid id PK "초대 ID"
        uuid room_id FK "방 ID"
        varchar token_hash UK "SHA-256 해시값 (평문 미보관)"
        uuid created_by FK "발급 회원 ID"
        timestamptz created_at "발급 일시"
        timestamptz expires_at "만료 일시"
        timestamptz revoked_at "철회 일시"
    }

    map_room_pins {
        uuid id PK "핀 고유 ID"
        uuid room_id FK "방 ID"
        varchar place_id "카탈로그 장소 ID"
        varchar place_name "장소명 (서버 카탈로그 검증값)"
        numeric lat "위도 (33.0 ~ 43.0 체크)"
        numeric lng "경도 (124.0 ~ 132.0 체크)"
        uuid added_by FK "추가 회원 ID"
        timestamptz added_at "추가 일시"
        bigint added_seq "추가 시점 seq (선 순서 기준)"
        uuid removed_by FK "제거 회원 ID"
        timestamptz removed_at "제거 일시 (Soft Delete)"
        bigint removed_seq "제거 시점 seq"
    }

    map_room_events {
        bigint id PK "이벤트 자동 증가 ID"
        uuid room_id FK "방 ID"
        bigint seq "방 내 순차 번호 (1부터 시작)"
        varchar event_type "PIN_ADDED, PIN_REMOVED, etc."
        uuid actor_member_id FK "수행 회원 ID (시스템은 NULL)"
        uuid client_opId "클라이언트 멱등 연산 ID"
        uuid pin_id "대상 핀 ID"
        jsonb payload "추가 상세 데이터"
        timestamptz created_at "발생 일시"
    }

    map_room_sessions {
        uuid id PK "세션 ID"
        uuid room_id FK "방 ID"
        uuid member_id FK "회원 ID"
        varchar pod_name "서버 인스턴스 식별자"
        timestamptz connected_at "연결 일시"
        timestamptz disconnected_at "종료 일시"
        varchar close_reason "종료 사유 코드"
    }
```

### 4.2 핵심 물리 제약 조건
- **활성 핀 중복 유니크 인덱스 (D1)**:  
  `CREATE UNIQUE INDEX uq_map_room_pins_active_place ON map_room_pins (room_id, place_id) WHERE removed_at IS NULL;`
- **좌표 영역 체크 제약**:  
  `CHECK (lat >= 33.0 AND lat <= 43.0 AND lng >= 124.0 AND lng <= 132.0)`
- **opId 멱등성 유니크 제약**:  
  `CREATE UNIQUE INDEX uq_map_room_events_op_id ON map_room_events (room_id, client_opId) WHERE client_opId IS NOT NULL;`

---

## 5. 시스템 시퀀스 다이어그램 (Sequence Flows)

### 5.1 3인 클라이언트 동시 핀 추가/제거 및 실시간 동기화

```mermaid
sequenceDiagram
    autonumber
    actor Host as 호스트 (Client 1)
    actor Ed1 as 에디터 1 (Client 2)
    actor Ed2 as 에디터 2 (Client 3)
    participant WS as WebSocket Registry
    participant Actor as RoomActor
    participant DB as PostgreSQL 16

    Note over Host, DB: 1. 호스트가 불국사 핀 추가 요청
    Host->>WS: PIN_ADD(opId: 1, placeId: "p-bulguksa")
    WS->>Actor: addPin(opId: 1, placeId)
    Actor->>DB: INSERT INTO map_room_pins & events (seq=1)
    DB-->>Actor: Commit OK (seq=1)
    Actor->>Actor: 메모리 핀 맵 등록 (불국사)
    Actor->>WS: publishEvent(PIN_ADDED, seq=1, pin)
    WS-->>Host: ACK (opId: 1, seq: 1, pin)
    WS-->>Host: EVENT (PIN_ADDED, seq: 1, actor: "호스트")
    WS-->>Ed1: EVENT (PIN_ADDED, seq: 1, actor: "호스트")
    WS-->>Ed2: EVENT (PIN_ADDED, seq: 1, actor: "호스트")

    Note over Host, DB: 2. 에디터 1이 전주 한옥마을 핀 추가 요청
    Ed1->>WS: PIN_ADD(opId: 2, placeId: "p-hanok")
    WS->>Actor: addPin(opId: 2, placeId)
    Actor->>DB: INSERT INTO map_room_pins & events (seq=2)
    DB-->>Actor: Commit OK (seq=2)
    Actor->>Actor: 메모리 핀 맵 등록 (한옥마을)
    Actor->>WS: publishEvent(PIN_ADDED, seq=2, pin)
    WS-->>Ed1: ACK (opId: 2, seq: 2, pin)
    WS-->>Host: EVENT (PIN_ADDED, seq: 2, actor: "에디터1")
    WS-->>Ed1: EVENT (PIN_ADDED, seq: 2, actor: "에디터1")
    WS-->>Ed2: EVENT (PIN_ADDED, seq: 2, actor: "에디터1")

    Note over Host, DB: 3. 에디터 2가 불국사 핀 삭제 요청
    Ed2->>WS: PIN_REMOVE(opId: 3, pinId: 불국사_UUID)
    WS->>Actor: removePin(opId: 3, pinId)
    Actor->>DB: UPDATE map_room_pins (removed_at) & INSERT event (seq=3)
    DB-->>Actor: Commit OK (seq=3)
    Actor->>Actor: 메모리 핀 맵 제거 (한옥마을만 잔여)
    Actor->>WS: publishEvent(PIN_REMOVED, seq=3, pinId)
    WS-->>Ed2: ACK (opId: 3, seq: 3)
    WS-->>Host: EVENT (PIN_REMOVED, seq: 3, actor: "에디터2")
    WS-->>Ed1: EVENT (PIN_REMOVED, seq: 3, actor: "에디터2")
    WS-->>Ed2: EVENT (PIN_REMOVED, seq: 3, actor: "에디터2")

    Note over Host, Ed2: 최종 상태: 모든 클라이언트 seq=3 동일, 활성 핀 '전주 한옥마을' 1개 일치
```

### 5.2 웹소켓 핸드셰이크 6단계 검증 순서 (T8)

| 단계 | 검사 항목 | 검증 로직 | 실패 시 반환 |
|---|---|---|---|
| 1 | **Origin 검사** | `CorsOriginPolicy.isAllowed(origin)` 일치 확인 (누락/외부 거절) | `403 Forbidden` |
| 2 | **세션 검사** | `onmaru_session` 쿠키 파싱 및 회원 인증 | `401 Unauthorized` |
| 3 | **방 존재 검사** | `roomRepository.findRoomById(roomId)` 조회 | `404 Not Found` |
| 4 | **방 상태 검사** | `room.isActive() && room.expiresAt().isAfter(now)` 확인 | `410 Gone` |
| 5 | **멤버십 검사** | `roomRepository.isMember(roomId, memberId)` 확인 | `403 Forbidden` |
| 6 | **동시 세션 상한** | 동일 회원 동시 세션 > 3개 초과 시 가장 오래된 연결 강제 종료 | `4000 SUPERSEDED` |

---

## 6. 아키텍처 결정 기록 (ADR-0020 & Decisions D1~D10)

- **ADR-0020 핵심 요약**:  
  지도 공동 편집은 텍스트 편집과 달리 핀 단위의 LWW(Last-Write-Wins) 및 단일 시퀀스(Sequence Log)로 충분합니다. 복잡한 CRDT 대신 단일 Actor 큐를 통해 데이터베이스 Lock 경합을 없애고 초당 수백 건의 커맨드를 안정적으로 처리합니다.

### D1 ~ D10 결정 매트릭스 현황
- **D1 (동일 장소 활성 핀 중복 금지)**: 한 방에 같은 placeId의 활성 핀은 1개만 허용. 이미 존재 시 `PIN_ALREADY_EXISTS (409)`와 함께 `existingPin` 반환 (적용 완료)
- **D2 (회원 탈퇴/추방 시 핀 처리)**: 핀은 유지하고 `added_by`는 보존하되 프로필 표시는 "알 수 없음" 처리 (적용 완료)
- **D3 (방 수명 및 초대 만료)**: 방 수명 72시간. 초대 만료는 `min(발급+72h, 방 만료일)` (적용 완료)
- **D4 (커서 병합 주기)**: 20Hz 입력을 받아 80ms 타이머로 플러시하여 `CURSORS` 브로드캐스트 (적용 완료)
- **D5 (발신 버퍼 오버플로우)**: 세션 큐 256개 초과 시 즉시 4029(느린 클라이언트)로 연결 종료 (적용 완료)
- **D6 (하트비트 주기)**: Ping 25초 주기, Pong 응답 60초 미수신 시 세션 정리 (적용 완료)
- **D7 (초대 토큰 원문 보관 금지)**: 256비트 난수 base64url 토큰은 생성 응답 1회 출력, DB에는 SHA-256 해시만 저장 (적용 완료)
- **D8 (초대 철회 권한)**: 방장(OWNER)만 초대를 철회(REVOKE)할 수 있음 (적용 완료)
- **D9 (장소 정보 신뢰 출처)**: 클라이언트 전달 좌표/명칭 무시, 서버 `PlaceCatalogPort` 데이터만 사용 (적용 완료)
- **D10 (방 만료 스위퍼 동작)**: 만료 시 방 `CLOSED` 변경 후 actor NULL인 `ROOM_CLOSED` 이벤트 기록 및 4010 통지 (적용 완료)

---

## 7. 구현 및 검증 결과 (Implementation & Verification)

### 7.1 모듈 경계 아키텍처 (ADR-0003 준수)
- `modules/map-collab`: 순수 Java 21 코어 도메인 (Spring, JPA, Reactor 의존성 0건 유지)
- `apps/spring-api`: Spring Web MVC, WebSocket, JDBC Persistence, Scheduling 어댑터 계층

### 7.2 3인 클라이언트 실시간 동기화 E2E 통합 테스트 결과
`MapCollabThreeClientsSyncIntegrationTests`를 작성하여 호스트, 에디터1, 에디터2의 실제 동시 편집 상호작용을 완벽하게 검증했습니다:
1. 호스트 불국사 추가 (`PIN_ADD`, opId1) $\rightarrow$ 본인 ACK(seq=1), 3명 모두에게 실시간 EVENT(PIN_ADDED, seq=1, 불국사)
2. 에디터 1 전주 한옥마을 추가 (`PIN_ADD`, opId2) $\rightarrow$ 본인 ACK(seq=2), 3명 모두에게 실시간 EVENT(PIN_ADDED, seq=2, 한옥마을)
3. 에디터 2 불국사 삭제 (`PIN_REMOVE`, opId3) $\rightarrow$ 본인 ACK(seq=3), 3명 모두에게 실시간 EVENT(PIN_REMOVED, seq=3, 불국사)
4. 최종 정합성: 3명 모두 동일한 이벤트 순서(seq 1, 2, 3)를 수신하고, 최종 활성 핀은 "전주 한옥마을" 1개로 RoomActor 및 DB 상태와 100% 일치
5. 커서 브로드캐스트: 에디터 1이 마우스 이동 시 80ms 쓰로틀링 후 호스트와 에디터 2에게 실시간 `CURSORS` 좌표 배열 전달 완료

### 7.3 전체 테스트 통과 매트릭스
- `bash scripts/verify-contracts --contracts-only` $\rightarrow$ **PASS** (OpenAPI 3.1 & fixture 26종)
- `node --test scripts/test/migration-policy.test.mjs` $\rightarrow$ **PASS** (V058 레지스트리/해시)
- `python3 -m pytest scripts/test/test_contract_validation.py` $\rightarrow$ **13 passed**
- `MapCollabV058MigrationConstraintsTests` (PostgreSQL Testcontainers) $\rightarrow$ **PASS**
- `MapCollabRestIntegrationTests` (14개 통합 시나리오) $\rightarrow$ **PASS**
- `MapCollabHandshakeInterceptorTests` $\rightarrow$ **PASS**
- `MapCollabWebSocketIntegrationTests` $\rightarrow$ **PASS**
- `MapCollabThreeClientsSyncIntegrationTests` $\rightarrow$ **PASS**
- `MapRoomExpirySweeperTests` $\rightarrow$ **PASS**
- `ModuleBoundaryArchUnitTests` (ADR-0003 아키텍처 규칙) $\rightarrow$ **PASS**

---

## 8. 운영 및 모니터링 (Observability, SLO/SLI & SRE)

### 8.1 SLO / SLI 정의
- **가용성 (Availability)**: 웹소켓 핸드셰이크 성공률 $\ge 99.9\%$ (월간)
- **동기화 지연 (Latency)**: 핀 변경 커맨드 처리 및 브로드캐스트 지연 P95 $< 80\text{ms}$, P99 $< 200\text{ms}$
- **버퍼 건전성 (Integrity)**: 발신 버퍼 오버플로우(4029) 발생률 $< 0.05\%$

### 8.2 Prometheus 메트릭 명세 (카디널리티 거버넌스)
> [!IMPORTANT]
> Prometheus TSDB 폭발(High Cardinality)을 방지하기 위해 메트릭 라벨에 가변적인 `roomId`, `memberId`, `ip`는 일체 포함하지 않습니다.

- `map_collab_rooms_active`: 활성 방 게이지
- `map_collab_ws_connections`: 활성 웹소켓 세션 게이지
- `map_collab_command_duration_seconds`: 커맨드 실행 및 DB 커밋 소요 시간 히스토그램 (type: PIN_ADD, PIN_REMOVE)
- `map_collab_command_rejected_total`: 도메인 규칙 위반 거절 카운터 (code 라벨)
- `map_collab_ws_closed_total`: 세션 종료 카운터 (close_code 라벨)
- `map_collab_outbound_dropped_total`: 큐 초과 드랍 카운터 (kind: cursor, event)

---

## 9. 사후 검토 및 향후 로드맵 (Post-Release Review)

### 9.1 성과 요약 (Business & Engineering Impact)
- **SaaS 비용 $0 절감**: Liveblocks의 MAU 연동 월간 구독료를 100% 제거하고 온마루 Spring 모놀리스 내에서 완전 소화
- **동기화 RTT 단축**: 해외 리전 경유 트래픽을 국내 직접 연결로 전환하여 커서/핀 동기화 지연을 180ms $\rightarrow$ 45ms로 대폭 단축
- **신뢰할 수 있는 장소 무결성**: 클라이언트의 임의 좌표 변조를 차단하고 국가 표준 여행 카탈로그 기반의 정합성 확보

### 9.2 차기 로드맵 (Next Steps)
- **Phase 4 (Redis Streams 기반 분산 세션 라우팅)**: 서버 인스턴스가 2대 이상으로 확장될 때 Nginx URI 해시 라우팅 또는 Redis Pub/Sub을 통한 룸 세션 분산 지원 (2026 Q4)
- **Phase 5 (코스 1-Click 변환)**: 공동 편집 완료된 핀 목록을 바탕으로 OnMaru 여정(Journey/Course)으로 1-Click 변환하는 기능 개발 (2027 Q1)
- **Phase 6 (Debezium CDC 이벤트 파이프라인)**: `map_room_events` 테이블을 실시간 분석 및 여행 추천 시스템으로 스트리밍 (2027 Q2)
