-- onmaru-checksum: kcontents-normalized-schema-v046-20261008
-- Issue #684: independent K-Contents facts; no legacy screen-hanok publication changes.

CREATE TABLE onmaru.k_contents (
    id uuid PRIMARY KEY,
    title varchar(300) NOT NULL CHECK (btrim(title) <> ''),
    normalized_title varchar(300) NOT NULL CHECK (btrim(normalized_title) <> ''),
    work_type varchar(20) NOT NULL CHECK (work_type IN ('DRAMA','MOVIE','VARIETY','MUSIC_VIDEO')),
    release_year smallint CHECK (release_year BETWEEN 1895 AND 2200),
    season_key varchar(80),
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('AUTO_VERIFIED','HUMAN_VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    verified_by varchar(160), verified_at timestamptz, rule_version varchar(80), model_version varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, work_type),
    CHECK (status NOT IN ('AUTO_VERIFIED','HUMAN_VERIFIED') OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX k_contents_identity_candidates_idx ON onmaru.k_contents (work_type, normalized_title, release_year, season_key);

CREATE TABLE onmaru.k_content_aliases (
    id uuid PRIMARY KEY, k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id),
    alias_text varchar(300) NOT NULL CHECK (btrim(alias_text) <> ''),
    normalized_alias varchar(300) NOT NULL CHECK (btrim(normalized_alias) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (k_content_id, normalized_alias)
);
CREATE INDEX k_content_alias_lookup_idx ON onmaru.k_content_aliases (normalized_alias);

CREATE TABLE onmaru.k_content_metadata_sources (
    id uuid PRIMARY KEY, k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id),
    canonical_url text NOT NULL CHECK (canonical_url ~ '^https?://[^[:space:]]+$'),
    source_type varchar(40) NOT NULL, title text NOT NULL CHECK (btrim(title) <> ''), publisher varchar(300),
    excerpt text, document_location text, published_at timestamptz, observed_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE','WITHDRAWN')),
    search_provider varchar(80), collection_run_id uuid,
    verified_by varchar(160), verified_at timestamptz, rule_version varchar(80), model_version varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (k_content_id, canonical_url), UNIQUE (id, k_content_id),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
);

CREATE TABLE onmaru.k_content_units (
    id uuid PRIMARY KEY, k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id),
    unit_type varchar(30) NOT NULL CHECK (unit_type IN ('SEASON','EPISODE','VERSION','SPECIAL')),
    unit_key varchar(100) NOT NULL CHECK (btrim(unit_key) <> ''), title varchar(300),
    UNIQUE (k_content_id, unit_type, unit_key), UNIQUE (id, k_content_id)
);

CREATE TABLE onmaru.k_content_creative_parties (
    id uuid PRIMARY KEY, name varchar(300) NOT NULL CHECK (btrim(name) <> ''),
    normalized_name varchar(300) NOT NULL CHECK (btrim(normalized_name) <> ''),
    party_type varchar(20) NOT NULL CHECK (party_type IN ('PERSON','GROUP')),
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('AUTO_VERIFIED','HUMAN_VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX k_content_party_candidate_idx ON onmaru.k_content_creative_parties (normalized_name, party_type);
CREATE TABLE onmaru.k_content_party_aliases (
    id uuid PRIMARY KEY, party_id uuid NOT NULL REFERENCES onmaru.k_content_creative_parties(id),
    alias_text varchar(300) NOT NULL, normalized_alias varchar(300) NOT NULL,
    UNIQUE (party_id, normalized_alias)
);
CREATE INDEX k_content_party_alias_lookup_idx ON onmaru.k_content_party_aliases (normalized_alias);
CREATE TABLE onmaru.k_content_credits (
    id uuid PRIMARY KEY, k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id),
    party_id uuid NOT NULL REFERENCES onmaru.k_content_creative_parties(id),
    source_id uuid NOT NULL, role varchar(40) NOT NULL CHECK (role IN ('ARTIST','GROUP','DIRECTOR','CAST','OTHER')),
    FOREIGN KEY (source_id, k_content_id) REFERENCES onmaru.k_content_metadata_sources(id, k_content_id),
    UNIQUE (k_content_id, party_id, role, source_id)
);

CREATE TABLE onmaru.k_content_place_relations (
    id uuid PRIMARY KEY,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity(id),
    k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id),
    relation_type varchar(40) NOT NULL DEFAULT 'FILMING_LOCATION' CHECK (relation_type = 'FILMING_LOCATION'),
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('AUTO_VERIFIED','HUMAN_VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    confidence numeric(5,4) CHECK (confidence BETWEEN 0 AND 1),
    verified_by varchar(160), verified_at timestamptz, rule_version varchar(80), model_version varchar(80),
    review_reason varchar(120), created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (place_id, k_content_id, relation_type), UNIQUE (id, k_content_id),
    CHECK (status NOT IN ('AUTO_VERIFIED','HUMAN_VERIFIED') OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX k_content_relations_place_public_idx ON onmaru.k_content_place_relations (place_id, status, k_content_id, id);
CREATE INDEX k_content_relations_work_public_idx ON onmaru.k_content_place_relations (k_content_id, status, place_id, id);

CREATE TABLE onmaru.k_content_relation_evidence (
    id uuid PRIMARY KEY, relation_id uuid NOT NULL REFERENCES onmaru.k_content_place_relations(id),
    canonical_url text NOT NULL CHECK (canonical_url ~ '^https?://[^[:space:]]+$'),
    source_type varchar(40) NOT NULL, title text NOT NULL CHECK (btrim(title) <> ''), publisher varchar(300),
    filming_excerpt text NOT NULL CHECK (btrim(filming_excerpt) <> ''), document_location text,
    published_at timestamptz, observed_at timestamptz NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE','WITHDRAWN')),
    search_provider varchar(80), collection_run_id uuid,
    verified_by varchar(160), verified_at timestamptz, rule_version varchar(80), model_version varchar(80),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (relation_id, canonical_url), UNIQUE (id, relation_id),
    CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL))
);
CREATE INDEX k_content_evidence_valid_idx ON onmaru.k_content_relation_evidence (relation_id, id) WHERE status = 'VERIFIED';

CREATE TABLE onmaru.k_content_appearance_contexts (
    id uuid PRIMARY KEY, relation_id uuid NOT NULL, k_content_id uuid NOT NULL,
    unit_id uuid, evidence_id uuid NOT NULL,
    scene_description text, document_location text,
    FOREIGN KEY (relation_id, k_content_id) REFERENCES onmaru.k_content_place_relations(id, k_content_id),
    FOREIGN KEY (unit_id, k_content_id) REFERENCES onmaru.k_content_units(id, k_content_id),
    FOREIGN KEY (evidence_id, relation_id) REFERENCES onmaru.k_content_relation_evidence(id, relation_id)
);

CREATE TABLE onmaru.k_content_tags (
    id uuid PRIMARY KEY, code varchar(100) NOT NULL UNIQUE,
    scope varchar(20) NOT NULL CHECK (scope IN ('WORK_TAG','RELATION_TAG')),
    group_code varchar(30) NOT NULL CHECK (group_code IN ('WORK_GENRE','WORK_THEME','RELATION_CONTEXT')),
    label_ko varchar(160) NOT NULL, label_en varchar(160), active boolean NOT NULL DEFAULT false,
    policy_version varchar(80) NOT NULL,
    CHECK ((scope = 'WORK_TAG' AND group_code IN ('WORK_GENRE','WORK_THEME')) OR
           (scope = 'RELATION_TAG' AND group_code = 'RELATION_CONTEXT')),
    UNIQUE (id, scope), UNIQUE (id, scope, group_code)
);
CREATE TABLE onmaru.k_content_tag_aliases (
    id uuid PRIMARY KEY, tag_id uuid NOT NULL REFERENCES onmaru.k_content_tags(id),
    scope varchar(20) NOT NULL, group_code varchar(30) NOT NULL,
    alias_text varchar(160) NOT NULL, normalized_alias varchar(160) NOT NULL,
    FOREIGN KEY (tag_id, scope, group_code) REFERENCES onmaru.k_content_tags(id, scope, group_code),
    UNIQUE (scope, group_code, normalized_alias)
);
CREATE TABLE onmaru.k_content_work_tags (
    k_content_id uuid NOT NULL REFERENCES onmaru.k_contents(id), tag_id uuid NOT NULL,
    scope varchar(20) NOT NULL DEFAULT 'WORK_TAG' CHECK (scope = 'WORK_TAG'),
    source_id uuid NOT NULL, status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    PRIMARY KEY (k_content_id, tag_id),
    FOREIGN KEY (tag_id, scope) REFERENCES onmaru.k_content_tags(id, scope),
    FOREIGN KEY (source_id, k_content_id) REFERENCES onmaru.k_content_metadata_sources(id, k_content_id)
);
CREATE TABLE onmaru.k_content_relation_tags (
    relation_id uuid NOT NULL REFERENCES onmaru.k_content_place_relations(id), tag_id uuid NOT NULL,
    scope varchar(20) NOT NULL DEFAULT 'RELATION_TAG' CHECK (scope = 'RELATION_TAG'),
    evidence_id uuid NOT NULL, status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    PRIMARY KEY (relation_id, tag_id),
    FOREIGN KEY (tag_id, scope) REFERENCES onmaru.k_content_tags(id, scope),
    FOREIGN KEY (evidence_id, relation_id) REFERENCES onmaru.k_content_relation_evidence(id, relation_id)
);
CREATE TABLE onmaru.k_content_tag_candidates (
    id uuid PRIMARY KEY, scope varchar(20) NOT NULL CHECK (scope IN ('WORK_TAG','RELATION_TAG')),
    k_content_id uuid REFERENCES onmaru.k_contents(id), relation_id uuid REFERENCES onmaru.k_content_place_relations(id),
    source_id uuid, evidence_id uuid,
    raw_label varchar(160) NOT NULL, normalized_label varchar(160) NOT NULL,
    group_hint varchar(30) NOT NULL, supporting_quote text NOT NULL,
    extraction_run_id uuid, model_version varchar(80), status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED'
       CHECK (status IN ('REVIEW_REQUIRED','VERIFIED','REJECTED')),
    resolved_tag_id uuid REFERENCES onmaru.k_content_tags(id), reviewed_by varchar(160), reviewed_at timestamptz,
    CHECK ((scope = 'WORK_TAG' AND k_content_id IS NOT NULL AND source_id IS NOT NULL AND relation_id IS NULL AND evidence_id IS NULL)
        OR (scope = 'RELATION_TAG' AND relation_id IS NOT NULL AND evidence_id IS NOT NULL AND k_content_id IS NULL AND source_id IS NULL)),
    FOREIGN KEY (source_id, k_content_id) REFERENCES onmaru.k_content_metadata_sources(id, k_content_id),
    FOREIGN KEY (evidence_id, relation_id) REFERENCES onmaru.k_content_relation_evidence(id, relation_id)
);
CREATE UNIQUE INDEX k_content_work_tag_candidate_uq ON onmaru.k_content_tag_candidates (k_content_id, source_id, normalized_label) WHERE scope = 'WORK_TAG';
CREATE UNIQUE INDEX k_content_relation_tag_candidate_uq ON onmaru.k_content_tag_candidates (relation_id, evidence_id, normalized_label) WHERE scope = 'RELATION_TAG';

CREATE TABLE onmaru.k_content_work_summary_points (
    id uuid PRIMARY KEY, k_content_id uuid NOT NULL, position smallint NOT NULL CHECK (position BETWEEN 1 AND 3),
    summary_text varchar(500) NOT NULL, source_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    generated_at timestamptz, prompt_version varchar(80), source_fingerprint varchar(64), edited_by varchar(160),
    FOREIGN KEY (source_id, k_content_id) REFERENCES onmaru.k_content_metadata_sources(id, k_content_id),
    UNIQUE (k_content_id, position)
);
CREATE TABLE onmaru.k_content_relation_summary_points (
    id uuid PRIMARY KEY, relation_id uuid NOT NULL, position smallint NOT NULL CHECK (position BETWEEN 1 AND 3),
    summary_text varchar(500) NOT NULL, evidence_id uuid NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'REVIEW_REQUIRED' CHECK (status IN ('VERIFIED','REVIEW_REQUIRED','REJECTED','STALE')),
    generated_at timestamptz, prompt_version varchar(80), source_fingerprint varchar(64), edited_by varchar(160),
    FOREIGN KEY (evidence_id, relation_id) REFERENCES onmaru.k_content_relation_evidence(id, relation_id),
    UNIQUE (relation_id, position)
);

-- Consumers must read this view instead of status-only relations. Evidence withdrawal is reflected immediately.
CREATE VIEW onmaru.k_content_public_relations AS
SELECT r.id, r.place_id, r.k_content_id, r.relation_type, r.verified_at
FROM onmaru.k_content_place_relations r
JOIN onmaru.k_contents w ON w.id = r.k_content_id
WHERE r.status IN ('AUTO_VERIFIED','HUMAN_VERIFIED')
  AND w.status IN ('AUTO_VERIFIED','HUMAN_VERIFIED')
  AND EXISTS (SELECT 1 FROM onmaru.k_content_relation_evidence e
              WHERE e.relation_id = r.id AND e.status = 'VERIFIED');

INSERT INTO onmaru_registry.migration_version_reservations (version, reserved_for, issue_number, description)
VALUES ('046', 'KCONTENTS_NORMALIZED_SCHEMA', 684, 'Normalized K-Contents works, place filming relations, evidence and public gate')
ON CONFLICT (version) DO UPDATE SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number, description = EXCLUDED.description;
