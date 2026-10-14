-- 상세 수집 결과. 공개 revision과 분리된 수집 전용 테이블이다.
-- 장소별 최신 시도 1행을 유지하며, 일일 호출 상한은 last_attempted_at으로 센다.
CREATE TABLE onmaru.tourapi_detail_snapshots (
    content_id varchar PRIMARY KEY,
    content_type_id varchar NOT NULL,
    category varchar NOT NULL,
    status varchar NOT NULL,
    overview_length integer NOT NULL DEFAULT 0,
    payload jsonb,
    payload_sha256 varchar,
    error_code varchar,
    attempts integer NOT NULL DEFAULT 0,
    last_attempted_at timestamptz NOT NULL,
    fetched_at timestamptz,
    CONSTRAINT tourapi_detail_snapshots_status_ck CHECK (status IN ('OK', 'FAILED')),
    CONSTRAINT tourapi_detail_snapshots_attempts_ck CHECK (attempts >= 0),
    CONSTRAINT tourapi_detail_snapshots_overview_length_ck CHECK (overview_length >= 0),
    CONSTRAINT tourapi_detail_snapshots_payload_object_ck CHECK (payload IS NULL OR jsonb_typeof(payload) = 'object')
);

CREATE INDEX tourapi_detail_snapshots_attempted_idx
    ON onmaru.tourapi_detail_snapshots (last_attempted_at);
