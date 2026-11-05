-- onmaru-checksum: journey-run-observations-v054-20261105
-- Issue #787: feat(observability): AI run trace와 Spring observation projection 구축

CREATE TABLE IF NOT EXISTS onmaru.discovery_run_observations (
    run_id uuid PRIMARY KEY REFERENCES onmaru.discovery_runs (id) ON DELETE CASCADE,
    trace_id varchar(32),
    observation_status varchar(32) NOT NULL,
    trace_coverage varchar(32) NOT NULL,
    route varchar(32) NOT NULL,
    outcome varchar(32) NOT NULL,
    corpus_revision varchar(128),
    config_hash varchar(128),
    prompt_version varchar(128),
    stages jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT discovery_run_observations_stages_array_ck CHECK (jsonb_typeof(stages) = 'array')
);

CREATE INDEX IF NOT EXISTS discovery_run_observations_trace_id_idx
    ON onmaru.discovery_run_observations (trace_id)
    WHERE trace_id IS NOT NULL;

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '054',
    'JOURNEY_RUN_OBSERVATIONS',
    787,
    'Sanitized AI run observation projection store and stage trace reference'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
