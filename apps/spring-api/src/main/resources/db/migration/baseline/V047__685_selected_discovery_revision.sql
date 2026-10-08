-- onmaru-checksum: selected-discovery-revision-v047-20261008
-- Issue #685. Independent discovery pointer; legacy kto-korean-tour active is never updated here.

CREATE TABLE onmaru.selected_discovery_runs (
    id uuid PRIMARY KEY,
    due_at timestamptz NOT NULL UNIQUE,
    status varchar(20) NOT NULL CHECK (status IN ('RUNNING','STAGED','PUBLISHED','FAILED')),
    detail_requests integer NOT NULL DEFAULT 0 CHECK (detail_requests >= 0),
    failure_code varchar(80),
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz
);

CREATE TABLE onmaru.selected_discovery_checkpoints (
    run_id uuid NOT NULL REFERENCES onmaru.selected_discovery_runs(id),
    operation varchar(40) NOT NULL,
    filter_value varchar(80) NOT NULL,
    page_number integer NOT NULL CHECK (page_number > 0),
    received integer NOT NULL CHECK (received >= 0),
    expected integer NOT NULL CHECK (expected >= 0),
    candidate_count integer NOT NULL CHECK (candidate_count >= 0),
    quarantine_count integer NOT NULL CHECK (quarantine_count >= 0),
    PRIMARY KEY (run_id, operation, filter_value, page_number)
);

CREATE TABLE onmaru.selected_discovery_revisions (
    id uuid PRIMARY KEY REFERENCES onmaru.selected_discovery_runs(id),
    base_revision_id uuid REFERENCES onmaru.selected_discovery_revisions(id),
    status varchar(20) NOT NULL CHECK (status IN ('STAGED','PUBLISHED')),
    policy_version varchar(80) NOT NULL,
    hash_schema_version varchar(80) NOT NULL,
    added_count integer NOT NULL,
    changed_count integer NOT NULL,
    unchanged_count integer NOT NULL,
    missing_count integer NOT NULL,
    quarantine_count integer NOT NULL,
    approved_count integer NOT NULL,
    detail_requests integer NOT NULL,
    counts_by_region jsonb NOT NULL,
    counts_by_role jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);

CREATE TABLE onmaru.selected_discovery_candidates (
    revision_id uuid NOT NULL REFERENCES onmaru.selected_discovery_revisions(id),
    content_id varchar(40) NOT NULL,
    raw jsonb NOT NULL,
    list_hash char(64) NOT NULL,
    detail_hash char(64) NOT NULL,
    hash_schema_version varchar(80) NOT NULL,
    policy_version varchar(80) NOT NULL,
    decision varchar(10) NOT NULL CHECK (decision IN ('INCLUDE','REVIEW','EXCLUDE')),
    role varchar(40),
    reason_code varchar(100) NOT NULL,
    diff_status varchar(10) NOT NULL CHECK (diff_status IN ('ADDED','CHANGED','UNCHANGED','MISSING')),
    modifiedtime varchar(30),
    PRIMARY KEY (revision_id, content_id)
);
CREATE INDEX selected_discovery_candidates_diff_idx ON onmaru.selected_discovery_candidates(revision_id, diff_status);

CREATE TABLE onmaru.selected_discovery_quarantines (
    id uuid PRIMARY KEY,
    revision_id uuid NOT NULL REFERENCES onmaru.selected_discovery_revisions(id),
    content_id varchar(40),
    reason_code varchar(100) NOT NULL,
    decision varchar(10) NOT NULL CHECK (decision IN ('INCLUDE','REVIEW','EXCLUDE')),
    policy_reason varchar(100) NOT NULL,
    raw jsonb NOT NULL
);
CREATE INDEX selected_discovery_quarantines_revision_idx ON onmaru.selected_discovery_quarantines(revision_id);

-- Operator-reviewed approvals are bound to both canonical hashes. A source change invalidates them.
CREATE TABLE onmaru.selected_discovery_approvals (
    content_id varchar(40) PRIMARY KEY,
    list_hash char(64) NOT NULL,
    detail_hash char(64) NOT NULL,
    role varchar(40) NOT NULL,
    source_fingerprint text NOT NULL CHECK (btrim(source_fingerprint) <> ''),
    detail_reviewed boolean NOT NULL CHECK (detail_reviewed),
    rights_reviewed boolean NOT NULL CHECK (rights_reviewed),
    evidence_ref text NOT NULL CHECK (btrim(evidence_ref) <> ''),
    approved_by varchar(160) NOT NULL CHECK (btrim(approved_by) <> ''),
    approved_at timestamptz NOT NULL
);

CREATE TABLE onmaru.selected_discovery_approval_audit (
    id uuid PRIMARY KEY,
    content_id varchar(40) NOT NULL,
    action varchar(10) NOT NULL CHECK (action IN ('APPROVE','REVOKE')),
    actor varchar(160) NOT NULL,
    list_hash char(64),
    detail_hash char(64),
    evidence_ref text,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX selected_discovery_approval_audit_content_idx ON onmaru.selected_discovery_approval_audit(content_id,recorded_at);

CREATE TABLE onmaru.selected_discovery_public_items (
    revision_id uuid NOT NULL REFERENCES onmaru.selected_discovery_revisions(id),
    content_id varchar(40) NOT NULL,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity(id),
    role varchar(40) NOT NULL,
    region_code varchar(20),
    raw jsonb NOT NULL,
    PRIMARY KEY (revision_id, content_id),
    UNIQUE (revision_id, place_id)
);
CREATE INDEX selected_discovery_public_region_role_idx ON onmaru.selected_discovery_public_items(revision_id, region_code, role);

CREATE TABLE onmaru.selected_discovery_active (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    revision_id uuid NOT NULL REFERENCES onmaru.selected_discovery_revisions(id),
    activated_at timestamptz NOT NULL
);

-- All new discovery readers use this view. Revoking approval hides an already active item immediately.
CREATE VIEW onmaru.selected_discovery_public_visible AS
SELECT p.revision_id, p.content_id, p.place_id, p.role, p.region_code, p.raw
FROM onmaru.selected_discovery_active active
JOIN onmaru.selected_discovery_public_items p ON p.revision_id = active.revision_id
JOIN onmaru.selected_discovery_candidates c
  ON c.revision_id = p.revision_id AND c.content_id = p.content_id
JOIN onmaru.selected_discovery_approvals approval ON approval.content_id = p.content_id
WHERE c.decision = 'INCLUDE' AND c.diff_status <> 'MISSING'
  AND c.list_hash = approval.list_hash AND c.detail_hash = approval.detail_hash
  AND p.role = approval.role AND approval.detail_reviewed AND approval.rights_reviewed
  AND btrim(approval.evidence_ref) <> '';

INSERT INTO onmaru_registry.migration_version_reservations (version, reserved_for, issue_number, description)
VALUES ('047', 'SELECTED_DISCOVERY_REVISION', 685, 'Independent weekly discovery candidates, approval gate and active revision')
ON CONFLICT (version) DO UPDATE SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number, description = EXCLUDED.description;
