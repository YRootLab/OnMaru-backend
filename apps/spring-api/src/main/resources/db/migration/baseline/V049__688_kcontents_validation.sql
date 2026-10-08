-- onmaru-checksum: kcontents-validation-v049-20261009
-- W7 is additive: neither legacy catalog pointers nor existing screen-hanok relations change.
ALTER TABLE onmaru.k_content_research_jobs
    ADD COLUMN schema_version varchar(80),
    ADD COLUMN prompt_version varchar(80),
    ADD COLUMN model_version varchar(80);

ALTER TABLE onmaru.k_content_research_evidence
    ADD COLUMN source_verified_at timestamptz,
    ADD COLUMN source_verified_by varchar(160),
    ADD CONSTRAINT k_content_research_evidence_source_verified_ck
      CHECK ((source_verified_at IS NULL) = (source_verified_by IS NULL));

CREATE TABLE onmaru.k_content_validation_runs (
    id uuid PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES onmaru.k_content_research_jobs(id),
    requeue_epoch integer NOT NULL CHECK (requeue_epoch >= 0),
    input_fingerprint char(64) NOT NULL,
    source_fingerprint varchar(128) NOT NULL,
    schema_version varchar(80) NOT NULL,
    prompt_version varchar(80) NOT NULL,
    model_version varchar(80) NOT NULL,
    rule_version varchar(80) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('AUTO_VERIFIED','REVIEW_REQUIRED','REJECTED','STALE','NO_MATCH')),
    candidate_count integer NOT NULL CHECK (candidate_count >= 0),
    validated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (job_id,requeue_epoch)
);
CREATE INDEX k_content_validation_runs_fingerprint_idx ON onmaru.k_content_validation_runs(input_fingerprint);

CREATE TABLE onmaru.k_content_validation_reviews (
    id uuid PRIMARY KEY,
    validation_run_id uuid NOT NULL REFERENCES onmaru.k_content_validation_runs(id),
    candidate_index integer NOT NULL CHECK (candidate_index >= 0),
    relation_id uuid REFERENCES onmaru.k_content_place_relations(id),
    reason_code varchar(100) NOT NULL,
    candidate_json jsonb NOT NULL,
    state varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (state IN ('OPEN','APPROVED','REJECTED')),
    reviewed_by varchar(160), reviewed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((state = 'OPEN') = (reviewed_by IS NULL AND reviewed_at IS NULL))
);
CREATE INDEX k_content_validation_reviews_open_idx ON onmaru.k_content_validation_reviews(created_at,id) WHERE state='OPEN';

CREATE TABLE onmaru.k_content_validation_audit (
    id uuid PRIMARY KEY,
    validation_run_id uuid NOT NULL REFERENCES onmaru.k_content_validation_runs(id),
    review_id uuid REFERENCES onmaru.k_content_validation_reviews(id),
    action varchar(30) NOT NULL,
    actor varchar(160) NOT NULL,
    detail_code varchar(100),
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX k_content_validation_audit_run_idx ON onmaru.k_content_validation_audit(validation_run_id,recorded_at);

INSERT INTO onmaru_registry.migration_version_reservations (version,reserved_for,issue_number,description)
VALUES ('049','KCONTENTS_EVIDENCE_VALIDATION',688,'Evidence-bound extraction validation, review queue and audit')
ON CONFLICT (version) DO UPDATE SET reserved_for=EXCLUDED.reserved_for,
    issue_number=EXCLUDED.issue_number,description=EXCLUDED.description;
