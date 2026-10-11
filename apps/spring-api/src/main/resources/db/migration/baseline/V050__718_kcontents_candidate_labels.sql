-- onmaru-checksum: kcontents-candidate-labels-v050-20261012
-- Issue #718. Full candidate labels are internal research state, not public filming claims.

CREATE TABLE onmaru.k_content_label_batches (
    id uuid PRIMARY KEY,
    captured_at timestamptz NOT NULL,
    policy_version varchar(80) NOT NULL,
    expected_count integer NOT NULL CHECK (expected_count > 0),
    label_set_sha256 char(64) NOT NULL CHECK (label_set_sha256 ~ '^[0-9a-f]{64}$'),
    official_source_sha256 char(64) CHECK (official_source_sha256 ~ '^[0-9a-f]{64}$'),
    evidence_audit_sha256 char(64) NOT NULL CHECK (evidence_audit_sha256 ~ '^[0-9a-f]{64}$'),
    place_summary_sha256 char(64) NOT NULL CHECK (place_summary_sha256 ~ '^[0-9a-f]{64}$'),
    status varchar(20) NOT NULL CHECK (status IN ('STAGED','PUBLISHED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE TABLE onmaru.k_content_candidate_labels (
    batch_id uuid NOT NULL REFERENCES onmaru.k_content_label_batches(id),
    content_id varchar(40) NOT NULL CHECK (content_id ~ '^[0-9]+$'),
    source_hash char(64) NOT NULL CHECK (source_hash ~ '^[0-9a-f]{64}$'),
    decision varchar(10) NOT NULL CHECK (decision IN ('INCLUDE','REVIEW','EXCLUDE')),
    reason_code varchar(100) NOT NULL,
    role varchar(40),
    research_status varchar(40) NOT NULL CHECK (research_status IN (
        'EVIDENCE_NOT_CHECKED','NO_OFFICIAL_MATCH_IN_DATASET',
        'OFFICIAL_CANDIDATE_REVIEW','VERIFIED_EXISTING')),
    label jsonb NOT NULL CHECK (jsonb_typeof(label) = 'object'),
    PRIMARY KEY (batch_id, content_id),
    CHECK (label->>'contentId' = content_id),
    CHECK (label->>'sourceHash' = source_hash),
    CHECK (label->>'decision' = decision),
    CHECK (label->>'kcontentsResearchStatus' = research_status),
    CHECK ((jsonb_typeof(label->'evidenceAudit') = 'object') IS TRUE),
    CHECK ((label->'evidenceAudit'->>'contentId' = content_id) IS TRUE),
    CHECK ((jsonb_typeof(label->'placeSummaryPoints') = 'array') IS TRUE),
    CHECK ((jsonb_array_length(label->'placeSummaryPoints') = 3) IS TRUE)
);
CREATE INDEX k_content_candidate_labels_decision_idx
    ON onmaru.k_content_candidate_labels(batch_id, decision, research_status, content_id);

CREATE TABLE onmaru.k_content_label_active (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    batch_id uuid NOT NULL REFERENCES onmaru.k_content_label_batches(id),
    activated_at timestamptz NOT NULL
);

CREATE VIEW onmaru.k_content_active_candidate_labels AS
SELECT label.*
FROM onmaru.k_content_label_active active
JOIN onmaru.k_content_label_batches batch ON batch.id = active.batch_id AND batch.status = 'PUBLISHED'
JOIN onmaru.k_content_candidate_labels label ON label.batch_id = active.batch_id;

INSERT INTO onmaru_registry.migration_version_reservations (version,reserved_for,issue_number,description)
VALUES ('050','KCONTENTS_CANDIDATE_LABELS',718,'Versioned full-candidate internal labels and active pointer')
ON CONFLICT (version) DO UPDATE SET reserved_for=EXCLUDED.reserved_for,
    issue_number=EXCLUDED.issue_number, description=EXCLUDED.description;
