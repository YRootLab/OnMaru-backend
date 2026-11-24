-- onmaru-checksum: ai-source-revocation-outbox-v060-20261123
-- Issue #875: durable ordered revocation events from catalog publication.

CREATE TABLE onmaru.ai_source_revocation_state (
    id smallint PRIMARY KEY CHECK (id = 1),
    sequence bigint NOT NULL CHECK (sequence >= 0),
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO onmaru.ai_source_revocation_state (id, sequence) VALUES (1, 0);

CREATE TABLE onmaru.ai_source_revocation_events (
    sequence bigint PRIMARY KEY CHECK (sequence > 0),
    scope varchar NOT NULL CHECK (scope IN ('PLACE', 'SOURCE_REVISION')),
    source_type varchar,
    source_id varchar,
    source_revision varchar,
    place_id uuid,
    revoked_at timestamptz NOT NULL,
    reason_code varchar NOT NULL,
    CONSTRAINT ai_source_revocation_target_ck CHECK (
        (scope = 'PLACE' AND place_id IS NOT NULL AND source_type IS NULL
            AND source_id IS NULL AND source_revision IS NULL)
        OR (scope = 'SOURCE_REVISION' AND place_id IS NULL AND source_type IS NOT NULL
            AND source_id IS NOT NULL AND source_revision IS NOT NULL)
    )
);

CREATE INDEX ai_source_revocation_events_target_idx
    ON onmaru.ai_source_revocation_events (scope, source_type, source_id, source_revision);

CREATE TABLE onmaru.ai_source_revocation_snapshots (
    snapshot_id uuid PRIMARY KEY,
    watermark bigint NOT NULL CHECK (watermark >= 0),
    active_revision_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE onmaru.ai_source_revocation_snapshot_places (
    snapshot_id uuid NOT NULL REFERENCES onmaru.ai_source_revocation_snapshots (snapshot_id)
        ON DELETE CASCADE,
    public_id varchar NOT NULL,
    PRIMARY KEY (snapshot_id, public_id)
);

-- Seed the ledger from retained published revisions so AI can replay withdrawals
-- that predate this migration. A place still present in the current active revision
-- is intentionally not revoked.
WITH active_revision AS (
    SELECT pointer.revision_id
    FROM onmaru.catalog_active_datasets pointer
    WHERE pointer.dataset = 'kto-korean-tour'
), historical_withdrawals AS (
    SELECT DISTINCT historical.revision_id,
           public_id.public_id AS source_id,
           now() AS revoked_at
    FROM onmaru.catalog_place_versions historical
    JOIN onmaru.catalog_dataset_revisions revision
      ON revision.id = historical.revision_id
    JOIN onmaru.catalog_place_public_ids public_id
      ON public_id.place_id = historical.place_id
    CROSS JOIN active_revision active
    WHERE revision.dataset = 'kto-korean-tour'
      AND revision.status = 'PUBLISHED'
      AND historical.status = 'ACTIVE'
      AND historical.revision_id <> active.revision_id
      AND NOT EXISTS (
          SELECT 1
          FROM onmaru.catalog_place_versions current_place
          WHERE current_place.revision_id = active.revision_id
            AND current_place.place_id = historical.place_id
            AND current_place.status = 'ACTIVE'
      )
), numbered AS (
    SELECT row_number() OVER (ORDER BY revision_id, source_id) AS sequence,
           revision_id,
           source_id,
           revoked_at
    FROM historical_withdrawals
)
INSERT INTO onmaru.ai_source_revocation_events (
    sequence, scope, source_type, source_id, source_revision, revoked_at, reason_code
)
SELECT sequence, 'SOURCE_REVISION', 'TOUR_API', source_id, revision_id::text,
       revoked_at, 'NOT_IN_CURRENT_ACTIVE_REVISION'
FROM numbered;

UPDATE onmaru.ai_source_revocation_state
SET sequence = (SELECT coalesce(max(sequence), 0) FROM onmaru.ai_source_revocation_events),
    updated_at = now()
WHERE id = 1;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '060', 'AI_SOURCE_REVOCATION_OUTBOX', 875,
    'Durable source revocation event ledger for AI Engine polling'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;

-- Independent of catalog revision retention: AI evidence can outlive BE snapshots.
CREATE TABLE onmaru.ai_source_revision_associations (
    public_id varchar NOT NULL,
    place_id uuid NOT NULL,
    revision_id uuid NOT NULL,
    PRIMARY KEY (public_id, revision_id)
);
INSERT INTO onmaru.ai_source_revision_associations (public_id, place_id, revision_id)
SELECT DISTINCT ids.public_id, place.place_id, place.revision_id
FROM onmaru.catalog_place_versions place
JOIN onmaru.catalog_place_public_ids ids USING(place_id)
JOIN onmaru.catalog_dataset_revisions revision ON revision.id=place.revision_id
WHERE revision.dataset='kto-korean-tour' AND revision.status='PUBLISHED'
  AND place.status='ACTIVE';
