-- onmaru-checksum: o08-v015-20260917
-- Issue: #125 O08 TTL, revision GC, member deletion cleanup, and restore deletion ledger.

CREATE TABLE onmaru.operations_retention_deletion_ledger (
    id uuid PRIMARY KEY,
    resource_type varchar NOT NULL,
    resource_id varchar NOT NULL,
    reason varchar NOT NULL,
    deleted_at timestamptz NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT operations_retention_deletion_ledger_resource_type_ck CHECK (btrim(resource_type) <> ''),
    CONSTRAINT operations_retention_deletion_ledger_resource_id_ck CHECK (btrim(resource_id) <> ''),
    CONSTRAINT operations_retention_deletion_ledger_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT operations_retention_deletion_ledger_details_object_ck CHECK (jsonb_typeof(details) = 'object')
);

CREATE UNIQUE INDEX operations_retention_deletion_ledger_resource_uq
    ON onmaru.operations_retention_deletion_ledger (resource_type, resource_id, reason);

CREATE INDEX operations_retention_deletion_ledger_deleted_at_idx
    ON onmaru.operations_retention_deletion_ledger (deleted_at);

CREATE INDEX identity_sessions_retention_expired_idx
    ON onmaru.identity_sessions (absolute_expires_at, token_hash)
    WHERE revoked_at IS NULL;

CREATE INDEX identity_guests_retention_expired_idx
    ON onmaru.identity_guests (expires_at, id)
    WHERE revoked_at IS NULL;

CREATE INDEX discovery_runs_retention_terminal_idx
    ON onmaru.discovery_runs (deadline_at, id)
    WHERE status IN ('COMPLETED', 'FAILED', 'CANCELLED');

CREATE INDEX discovery_proposals_retention_expired_idx
    ON onmaru.discovery_proposals (expires_at, id);

CREATE INDEX catalog_dataset_revisions_retention_inactive_idx
    ON onmaru.catalog_dataset_revisions (dataset, published_at, id)
    WHERE status <> 'PUBLISHED';

CREATE FUNCTION onmaru.block_saved_write_for_deleting_member()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM onmaru.identity_deletion_ledger ledger
        WHERE ledger.member_id = NEW.member_id
          AND ledger.status IN ('REQUESTED', 'COMPLETED')
    ) THEN
        RAISE EXCEPTION 'member deletion ledger blocks saved resource writes'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER journey_saved_resources_member_deletion_block_trg
    BEFORE INSERT OR UPDATE OF member_id
    ON onmaru.journey_saved_resources
    FOR EACH ROW
    EXECUTE FUNCTION onmaru.block_saved_write_for_deleting_member();

CREATE TRIGGER journey_saved_journeys_member_deletion_block_trg
    BEFORE INSERT OR UPDATE OF member_id
    ON onmaru.journey_saved_journeys
    FOR EACH ROW
    EXECUTE FUNCTION onmaru.block_saved_write_for_deleting_member();

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '015',
    'O08',
    125,
    'TTL cleanup, revision GC, member deletion cleanup, and replayable deletion ledger'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
