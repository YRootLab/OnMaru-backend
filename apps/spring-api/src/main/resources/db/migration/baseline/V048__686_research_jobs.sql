-- onmaru-checksum: kcontents-research-jobs-v048-20261008
-- Issue #686: durable worker leases and immutable submission receipts; no publication side effects.
CREATE TABLE onmaru.k_content_research_jobs (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity(id),
    reason varchar(60) NOT NULL,
    source_fingerprint varchar(128) NOT NULL,
    input_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    status varchar(20) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','LEASED','SUCCEEDED','FAILED','QUARANTINED')),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    requeue_epoch integer NOT NULL DEFAULT 0 CHECK (requeue_epoch >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts BETWEEN 1 AND 20),
    available_at timestamptz NOT NULL DEFAULT now(),
    lease_owner varchar(100),
    lease_token_hash char(64),
    lease_expires_at timestamptz,
    result_status varchar(20) CHECK (result_status IN ('MATCH','NO_MATCH','UNCERTAIN')),
    result_json jsonb,
    completed_at timestamptz,
    last_failure_code varchar(40),
    last_idempotency_key varchar(100),
    last_request_hash char(64),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (place_id, reason, source_fingerprint),
    CHECK ((status = 'LEASED') = (lease_owner IS NOT NULL AND lease_token_hash IS NOT NULL AND lease_expires_at IS NOT NULL))
);
CREATE INDEX k_content_research_jobs_ready_idx ON onmaru.k_content_research_jobs (available_at, created_at, id)
    WHERE status IN ('QUEUED','LEASED');
CREATE TABLE onmaru.k_content_research_evidence (
    id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES onmaru.k_content_research_jobs(id),
    canonical_url text NOT NULL CHECK (canonical_url ~ '^https?://[^[:space:]]+$'),
    title text NOT NULL CHECK (btrim(title) <> ''), publisher varchar(300),
    excerpt varchar(1000) NOT NULL CHECK (btrim(excerpt) <> ''),
    observed_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, canonical_url), UNIQUE (job_id, id)
);
CREATE TABLE onmaru.k_content_research_receipts (
    id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES onmaru.k_content_research_jobs(id),
    idempotency_key varchar(100) NOT NULL, request_hash char(64) NOT NULL,
    outcome varchar(20) NOT NULL CHECK (outcome IN ('QUEUED','SUCCEEDED','FAILED','QUARANTINED')),
    worker_id varchar(100) NOT NULL, lease_token_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id, idempotency_key)
);
CREATE TABLE onmaru.k_content_research_runs (
    id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES onmaru.k_content_research_jobs(id),
    requeue_epoch integer NOT NULL, attempt integer NOT NULL, worker_id varchar(100) NOT NULL,
    started_at timestamptz NOT NULL DEFAULT now(), finished_at timestamptz,
    outcome varchar(20) CHECK (outcome IN ('SUCCEEDED','FAILED','RETRY','QUARANTINED','EXPIRED')),
    failure_code varchar(40), UNIQUE(job_id,requeue_epoch,attempt)
);
CREATE TABLE onmaru.k_content_research_events (
    id uuid PRIMARY KEY, job_id uuid NOT NULL REFERENCES onmaru.k_content_research_jobs(id),
    event_type varchar(40) NOT NULL, actor varchar(100) NOT NULL,
    detail_code varchar(80), created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO onmaru_registry.migration_version_reservations (version, reserved_for, issue_number, description)
VALUES ('048', 'KCONTENTS_RESEARCH_JOBS', 686, 'Durable K-Contents research leases, evidence, receipts and audit')
ON CONFLICT (version) DO UPDATE SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number, description = EXCLUDED.description;
