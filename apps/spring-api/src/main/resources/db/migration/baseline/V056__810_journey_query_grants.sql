-- onmaru-checksum: journey-query-grant-v056-20261109
-- Issue #810: run/plan-bound approved query grant store (hash-only, one use, expiry bound)

CREATE TABLE IF NOT EXISTS onmaru.journey_query_grants (
    token_hash char(64) PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES onmaru.discovery_runs (id) ON DELETE CASCADE,
    plan_hash varchar(80) NOT NULL,
    filters jsonb NOT NULL,
    canonical_fields jsonb NOT NULL,
    versions jsonb NOT NULL,
    max_places integer NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT journey_query_grants_token_hash_ck CHECK (token_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT journey_query_grants_plan_hash_ck CHECK (plan_hash ~ '^sha256:[a-f0-9]{64}$'),
    CONSTRAINT journey_query_grants_max_places_ck CHECK (max_places BETWEEN 1 AND 5),
    CONSTRAINT journey_query_grants_expiry_ck CHECK (expires_at > created_at),
    CONSTRAINT journey_query_grants_consumed_ck CHECK (consumed_at IS NULL OR consumed_at >= created_at),
    CONSTRAINT journey_query_grants_json_shape_ck CHECK (
        jsonb_typeof(filters) = 'object'
        AND jsonb_typeof(canonical_fields) = 'array'
        AND jsonb_typeof(versions) = 'object'
    )
);

-- One approved grant per run; a replayed issue for the same run is rejected by the store.
CREATE UNIQUE INDEX IF NOT EXISTS journey_query_grants_run_id_uidx
    ON onmaru.journey_query_grants (run_id);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '056',
    'JOURNEY_QUERY_GRANT',
    810,
    'Run-bound approved query grant store with hash-only reference and one-time consumption'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
