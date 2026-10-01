-- onmaru-checksum: admin-foundation-v034-20260929
-- Issue: #375 Admin accounts, sessions, sanctions, curation overrides, and audit log.

CREATE TYPE onmaru.identity_admin_role AS ENUM ('ADMIN', 'EDITOR');
CREATE TYPE onmaru.identity_admin_account_status AS ENUM ('ACTIVE', 'SUSPENDED', 'DISABLED');
CREATE TYPE onmaru.identity_member_sanction_status AS ENUM ('ACTIVE', 'REVOKED', 'EXPIRED');

CREATE TABLE onmaru.identity_admin_accounts (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL,
    password_hash varchar(255) NOT NULL,
    nickname varchar(80) NOT NULL,
    role onmaru.identity_admin_role NOT NULL,
    status onmaru.identity_admin_account_status NOT NULL,
    last_login_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_admin_accounts_email_ck CHECK (length(email) BETWEEN 3 AND 254),
    CONSTRAINT identity_admin_accounts_nickname_ck CHECK (length(btrim(nickname)) BETWEEN 1 AND 80)
);

CREATE UNIQUE INDEX identity_admin_accounts_email_lower_uq
    ON onmaru.identity_admin_accounts (lower(email));

CREATE TABLE onmaru.identity_admin_sessions (
    token_hash varchar(64) PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    rotated_to_hash varchar(64),
    CONSTRAINT identity_admin_sessions_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_admin_sessions_seen_ck CHECK (last_seen_at >= created_at),
    CONSTRAINT identity_admin_sessions_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX identity_admin_sessions_admin_id_idx
    ON onmaru.identity_admin_sessions (admin_id);
CREATE INDEX identity_admin_sessions_expires_at_idx
    ON onmaru.identity_admin_sessions (expires_at)
    WHERE revoked_at IS NULL;

CREATE TABLE onmaru.identity_member_sanctions (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    status onmaru.identity_member_sanction_status NOT NULL,
    reason varchar(300) NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    created_by uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    revoked_by uuid REFERENCES onmaru.identity_admin_accounts (id),
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_member_sanctions_reason_ck CHECK (length(btrim(reason)) BETWEEN 1 AND 300),
    CONSTRAINT identity_member_sanctions_period_ck CHECK (ends_at IS NULL OR ends_at > starts_at)
);

CREATE UNIQUE INDEX identity_member_sanctions_one_active_idx
    ON onmaru.identity_member_sanctions (member_id)
    WHERE status = 'ACTIVE' AND revoked_at IS NULL;
CREATE INDEX identity_member_sanctions_member_id_idx
    ON onmaru.identity_member_sanctions (member_id, created_at DESC);

CREATE TABLE onmaru.catalog_admin_curation_overrides (
    id uuid PRIMARY KEY,
    canonical_place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    category varchar(20) NOT NULL,
    included boolean NOT NULL,
    badges jsonb NOT NULL DEFAULT '[]'::jsonb,
    source_revision_id uuid REFERENCES onmaru.catalog_dataset_revisions (id),
    version bigint NOT NULL,
    updated_by uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT catalog_admin_curation_category_ck CHECK (category IN ('VILLAGE', 'STAY', 'ROUTE')),
    CONSTRAINT catalog_admin_curation_badges_ck CHECK (jsonb_typeof(badges) = 'array'),
    CONSTRAINT catalog_admin_curation_version_ck CHECK (version > 0),
    UNIQUE (canonical_place_id, category, version)
);

CREATE INDEX catalog_admin_curation_lookup_idx
    ON onmaru.catalog_admin_curation_overrides (category, included, updated_at DESC);

CREATE TABLE onmaru.identity_admin_audit_log (
    id uuid PRIMARY KEY,
    actor_admin_id uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    action varchar(80) NOT NULL,
    resource_type varchar(80) NOT NULL,
    resource_id varchar(160),
    reason varchar(300),
    note varchar(1000),
    before_state jsonb,
    after_state jsonb,
    request_id uuid,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_admin_audit_action_ck CHECK (length(btrim(action)) BETWEEN 1 AND 80),
    CONSTRAINT identity_admin_audit_resource_type_ck CHECK (length(btrim(resource_type)) BETWEEN 1 AND 80),
    CONSTRAINT identity_admin_audit_before_state_ck CHECK (before_state IS NULL OR jsonb_typeof(before_state) = 'object'),
    CONSTRAINT identity_admin_audit_after_state_ck CHECK (after_state IS NULL OR jsonb_typeof(after_state) = 'object')
);

CREATE INDEX identity_admin_audit_actor_created_idx
    ON onmaru.identity_admin_audit_log (actor_admin_id, created_at DESC);
CREATE INDEX identity_admin_audit_resource_created_idx
    ON onmaru.identity_admin_audit_log (resource_type, resource_id, created_at DESC);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '034',
    'ADMIN_FOUNDATION',
    375,
    'Admin accounts, sessions, member sanctions, curation overrides, and append-only audit log'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
