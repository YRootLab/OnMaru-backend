-- onmaru-checksum: j10-v016-20260920
-- Issue: #77 follow-up - reconcile a missing discovery_runs relation.

DO $$
BEGIN
    IF to_regclass('onmaru.discovery_runs') IS NULL THEN
        IF to_regclass('onmaru.discovery_explorations') IS NULL
                OR to_regtype('onmaru.discovery_run_status') IS NULL THEN
            RAISE EXCEPTION
                'Cannot reconcile onmaru.discovery_runs: V006 discovery schema prerequisites are missing';
        END IF;

        CREATE TABLE onmaru.discovery_runs (
            id uuid PRIMARY KEY,
            exploration_id uuid NOT NULL REFERENCES onmaru.discovery_explorations (id),
            actor_key varchar NOT NULL,
            base_version integer NOT NULL,
            status onmaru.discovery_run_status NOT NULL,
            stage varchar,
            outcome varchar,
            clarification jsonb,
            created_at timestamptz NOT NULL,
            deadline_at timestamptz NOT NULL,
            started_at timestamptz,
            lease_expires_at timestamptz,
            generation integer NOT NULL,
            error_code varchar,
            engine varchar NOT NULL,
            CONSTRAINT discovery_runs_base_version_ck CHECK (base_version >= 0),
            CONSTRAINT discovery_runs_actor_key_ck CHECK (btrim(actor_key) <> ''),
            CONSTRAINT discovery_runs_generation_ck CHECK (generation > 0),
            CONSTRAINT discovery_runs_deadline_ck CHECK (deadline_at > created_at),
            CONSTRAINT discovery_runs_engine_ck CHECK (btrim(engine) <> ''),
            CONSTRAINT discovery_runs_status_outcome_ck CHECK (
                (status IN ('QUEUED', 'RUNNING') AND outcome IS NULL)
                OR (status = 'COMPLETED' AND outcome IS NOT NULL)
                OR (status IN ('FAILED', 'CANCELLED') AND outcome IS NULL)
            ),
            CONSTRAINT discovery_runs_stage_ck CHECK (
                stage IS NULL OR stage IN ('INTERPRETING', 'RETRIEVING', 'VALIDATING', 'PERSISTING')
            ),
            CONSTRAINT discovery_runs_clarification_object_ck CHECK (
                clarification IS NULL OR jsonb_typeof(clarification) = 'object'
            )
        );

        CREATE INDEX discovery_runs_exploration_id_idx
            ON onmaru.discovery_runs (exploration_id);
        CREATE INDEX discovery_runs_actor_key_idx
            ON onmaru.discovery_runs (actor_key);
        CREATE INDEX discovery_runs_deadline_at_idx
            ON onmaru.discovery_runs (deadline_at);
        CREATE INDEX discovery_runs_active_lease_expires_at_idx
            ON onmaru.discovery_runs (lease_expires_at)
            WHERE status = 'RUNNING';
        CREATE UNIQUE INDEX discovery_runs_active_exploration_uq
            ON onmaru.discovery_runs (exploration_id)
            WHERE status IN ('QUEUED', 'RUNNING');
        CREATE UNIQUE INDEX discovery_runs_active_actor_uq
            ON onmaru.discovery_runs (actor_key)
            WHERE status IN ('QUEUED', 'RUNNING');
    END IF;
END
$$;

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '016',
    'J10',
    77,
    'Reconcile a missing discovery_runs relation without manual production DDL'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
