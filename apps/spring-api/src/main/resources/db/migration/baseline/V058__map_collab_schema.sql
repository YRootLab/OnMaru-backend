-- onmaru-checksum: map-collab-v058-20261110
-- Issue: #777 / #781 지도 공동 편집(방·초대·참여자·핀·이벤트) 스키마 (v058)
-- 설계 원칙
--  * 방 내 순서의 단일 권위는 map_room_events.seq 이다 (room_id 단위 단조 증가).
--    핀 순서(= 선의 순서)는 added_seq 로 결정되므로 Kafka 파티션 키(room_id)와 1:1 대응한다.
--  * 모든 상태 변경은 map_room_events 행과 같은 트랜잭션으로 기록한다 (outbox 겸용, 추후 CDC 발행 가능).
--  * 커서·presence·연결 객체는 저장하지 않는다 (메모리/Redis 대상).
--  * 장소 식별자는 FE 가 쓰는 공개 ID(p-...) 이며 catalog_place_public_ids 를 참조한다.

CREATE TYPE onmaru.map_room_status AS ENUM ('ACTIVE', 'CLOSED');
CREATE TYPE onmaru.map_room_role AS ENUM ('OWNER', 'MEMBER');
CREATE TYPE onmaru.map_room_event_type AS ENUM (
    'ROOM_CREATED',
    'INVITE_ISSUED',
    'INVITE_REVOKED',
    'MEMBER_JOINED',
    'PIN_ADDED',
    'PIN_REMOVED',
    'ROOM_CLOSED'
);

-- 방 ----------------------------------------------------------------------
CREATE TABLE onmaru.map_rooms (
    id              uuid PRIMARY KEY,
    created_by      uuid NOT NULL REFERENCES onmaru.identity_members (id),
    status          onmaru.map_room_status NOT NULL DEFAULT 'ACTIVE',
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    closed_at       timestamptz,
    closed_by       uuid REFERENCES onmaru.identity_members (id),
    -- 마지막으로 발급한 room 내 이벤트 seq. 재기동 후에도 단조 증가를 보장하기 위해 영속화한다.
    last_event_seq  bigint NOT NULL DEFAULT 0 CHECK (last_event_seq >= 0),
    CONSTRAINT map_rooms_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT map_rooms_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- 만료 스위퍼: 활성 방만 expires_at 순으로 훑는다.
CREATE INDEX map_rooms_active_expires_at_idx
    ON onmaru.map_rooms (expires_at)
    WHERE status = 'ACTIVE';

CREATE INDEX map_rooms_created_by_idx
    ON onmaru.map_rooms (created_by);

-- 초대 링크 --------------------------------------------------------------
-- FE 의 '친구 초대' 는 링크를 만들고 72시간 유지한다. 방 ID 자체를 비밀로 쓰지 않도록
-- 초대 토큰을 별도 행으로 두고 해시만 저장한다 (원문 토큰은 발급 응답에서 한 번만 노출).
CREATE TABLE onmaru.map_room_invites (
    id              uuid PRIMARY KEY,
    room_id         uuid NOT NULL REFERENCES onmaru.map_rooms (id) ON DELETE CASCADE,
    token_hash      varchar(64) NOT NULL,
    created_by      uuid NOT NULL REFERENCES onmaru.identity_members (id),
    created_at      timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    revoked_at      timestamptz,
    CONSTRAINT map_room_invites_token_hash_uq UNIQUE (token_hash),
    CONSTRAINT map_room_invites_token_hash_format_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT map_room_invites_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX map_room_invites_room_id_idx
    ON onmaru.map_room_invites (room_id);

CREATE INDEX map_room_invites_expires_at_idx
    ON onmaru.map_room_invites (expires_at)
    WHERE revoked_at IS NULL;

-- 참여자 (멤버십: 한 번이라도 입장한 회원) -------------------------------
CREATE TABLE onmaru.map_room_members (
    room_id     uuid NOT NULL REFERENCES onmaru.map_rooms (id) ON DELETE CASCADE,
    member_id   uuid NOT NULL REFERENCES onmaru.identity_members (id),
    role        onmaru.map_room_role NOT NULL,
    joined_at   timestamptz NOT NULL,
    PRIMARY KEY (room_id, member_id)
);

-- "내가 참여 중인 방" 목록: 최근 입장 순
CREATE INDEX map_room_members_member_joined_at_idx
    ON onmaru.map_room_members (member_id, joined_at DESC);

-- 접속 이력 (연결 1회당 1행, 서버 발급 UUID만 사용) --------------------------
CREATE TABLE onmaru.map_room_sessions (
    id                  uuid PRIMARY KEY,
    room_id             uuid NOT NULL REFERENCES onmaru.map_rooms (id) ON DELETE CASCADE,
    member_id           uuid NOT NULL REFERENCES onmaru.identity_members (id),
    -- 연결을 잡고 있던 애플리케이션 인스턴스 식별자 (다중 인스턴스 라우팅·장애 추적용)
    instance_id         varchar(64) NOT NULL,
    connected_at        timestamptz NOT NULL,
    disconnected_at     timestamptz,
    end_reason          varchar(32),
    CONSTRAINT map_room_sessions_end_ck CHECK (
        (disconnected_at IS NULL) = (end_reason IS NULL)
    ),
    CONSTRAINT map_room_sessions_end_reason_ck CHECK (
        end_reason IS NULL OR end_reason IN (
            'CLIENT_CLOSED', 'HEARTBEAT_TIMEOUT', 'SLOW_CONSUMER',
            'ROOM_CLOSED', 'SERVER_SHUTDOWN', 'AUTH_EXPIRED', 'SUPERSEDED'
        )
    )
);

CREATE INDEX map_room_sessions_room_connected_at_idx
    ON onmaru.map_room_sessions (room_id, connected_at);

-- 회원별 접속 이력 (최근 순)
CREATE INDEX map_room_sessions_member_connected_at_idx
    ON onmaru.map_room_sessions (member_id, connected_at DESC);

-- 이벤트 로그 (room 내 단일 순서 권위, 감사 이력, outbox 겸용) ---------------
CREATE TABLE onmaru.map_room_events (
    room_id           uuid NOT NULL REFERENCES onmaru.map_rooms (id) ON DELETE CASCADE,
    seq               bigint NOT NULL CHECK (seq > 0),
    event_type        onmaru.map_room_event_type NOT NULL,
    -- 시스템 이벤트(만료 정리 등)는 NULL
    actor_member_id   uuid REFERENCES onmaru.identity_members (id),
    session_id        uuid REFERENCES onmaru.map_room_sessions (id) ON DELETE SET NULL,
    -- 클라이언트 멱등 키 (재전송된 연산을 한 번만 반영)
    client_op_id      uuid,
    pin_id            uuid,
    payload           jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at        timestamptz NOT NULL,
    PRIMARY KEY (room_id, seq),
    CONSTRAINT map_room_events_payload_object_ck CHECK (jsonb_typeof(payload) = 'object')
);

CREATE UNIQUE INDEX map_room_events_client_op_id_uq
    ON onmaru.map_room_events (room_id, client_op_id)
    WHERE client_op_id IS NOT NULL;

CREATE INDEX map_room_events_actor_idx
    ON onmaru.map_room_events (actor_member_id, created_at)
    WHERE actor_member_id IS NOT NULL;

-- 핀 (현재 상태 + 누가 찍고 지웠는지) ------------------------------------
CREATE TABLE onmaru.map_room_pins (
    room_id             uuid NOT NULL REFERENCES onmaru.map_rooms (id) ON DELETE CASCADE,
    pin_id              uuid NOT NULL,                       -- 클라이언트 생성, 방 내 고유
    -- FE placeId 는 카탈로그 공개 ID (p-...)
    place_public_id     varchar NOT NULL REFERENCES onmaru.catalog_place_public_ids (public_id),
    -- 찍은 시점의 장소 스냅샷 (FE 가 이름·좌표로 선을 그린다. 카탈로그 변경과 분리)
    place_name          varchar(80) NOT NULL,
    lat                 numeric(9,6) NOT NULL,
    lng                 numeric(9,6) NOT NULL,
    added_by            uuid NOT NULL REFERENCES onmaru.identity_members (id),
    added_at            timestamptz NOT NULL,
    added_seq           bigint NOT NULL CHECK (added_seq > 0),   -- 선의 순서
    removed_by          uuid REFERENCES onmaru.identity_members (id),
    removed_at          timestamptz,
    removed_seq         bigint CHECK (removed_seq IS NULL OR removed_seq > added_seq),
    PRIMARY KEY (room_id, pin_id),
    CONSTRAINT map_room_pins_name_ck CHECK (char_length(place_name) BETWEEN 1 AND 80),
    CONSTRAINT map_room_pins_lat_ck CHECK (lat BETWEEN -90 AND 90),
    CONSTRAINT map_room_pins_lng_ck CHECK (lng BETWEEN -180 AND 180),
    CONSTRAINT map_room_pins_removed_ck CHECK (
        (removed_at IS NULL) = (removed_by IS NULL)
        AND (removed_at IS NULL) = (removed_seq IS NULL)
    ),
    CONSTRAINT map_room_pins_added_seq_uq UNIQUE (room_id, added_seq)
);

-- 활성 핀을 순서대로 읽는 경로 (방 진입 스냅샷, 선 그리기)
CREATE INDEX map_room_pins_active_order_idx
    ON onmaru.map_room_pins (room_id, added_seq)
    WHERE removed_at IS NULL;

-- 같은 방에서 같은 장소는 활성 핀 1개만 허용 (결정 사항 — ADR 에 기록)
CREATE UNIQUE INDEX map_room_pins_active_place_uq
    ON onmaru.map_room_pins (room_id, place_public_id)
    WHERE removed_at IS NULL;

-- "내가 찍은 핀" 조회 (프로필/감사). 삭제자 조회는 map_room_events 로 처리하므로 인덱스를 두지 않는다.
CREATE INDEX map_room_pins_added_by_idx
    ON onmaru.map_room_pins (added_by);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '058',
    'MAP_COLLAB',
    825,
    'Map collaboration rooms, invites, members, sessions, pins and event log'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
