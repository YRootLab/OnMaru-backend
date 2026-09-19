-- onmaru-checksum: d03-v003-20260915
-- Issue: #76 D03 Member, OAuth identity, session, and guest grant schema.

CREATE TYPE onmaru.identity_member_status AS ENUM ('ACTIVE', 'DELETING');
CREATE TYPE onmaru.identity_deletion_status AS ENUM ('REQUESTED', 'COMPLETED', 'FAILED');

CREATE TABLE onmaru.identity_members (
    id uuid PRIMARY KEY,
    status onmaru.identity_member_status NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE TABLE onmaru.identity_external_accounts (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    provider varchar NOT NULL,
    issuer varchar NOT NULL,
    subject varchar NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT identity_external_accounts_provider_issuer_subject_uq
        UNIQUE (provider, issuer, subject)
);

CREATE INDEX identity_external_accounts_member_id_idx
    ON onmaru.identity_external_accounts (member_id);

CREATE TABLE onmaru.identity_sessions (
    token_hash varchar PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    absolute_expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT identity_sessions_token_hash_format_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_sessions_seen_window_ck CHECK (last_seen_at >= created_at)
);

CREATE INDEX identity_sessions_member_id_idx
    ON onmaru.identity_sessions (member_id);
CREATE INDEX identity_sessions_absolute_expires_at_idx
    ON onmaru.identity_sessions (absolute_expires_at);

CREATE VIEW onmaru.identity_valid_sessions AS
SELECT token_hash,
       member_id,
       created_at,
       last_seen_at,
       absolute_expires_at
FROM onmaru.identity_sessions
WHERE revoked_at IS NULL
  AND absolute_expires_at > CURRENT_TIMESTAMP;

CREATE TABLE onmaru.identity_guests (
    id uuid PRIMARY KEY,
    token_hash varchar NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    CONSTRAINT identity_guests_token_hash_format_ck CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX identity_guests_expires_at_idx
    ON onmaru.identity_guests (expires_at);

CREATE VIEW onmaru.identity_valid_guests AS
SELECT id,
       token_hash,
       expires_at
FROM onmaru.identity_guests
WHERE revoked_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP;

CREATE TABLE onmaru.identity_oauth_states (
    state_hash varchar PRIMARY KEY,
    guest_id uuid REFERENCES onmaru.identity_guests (id),
    browser_nonce_hash varchar NOT NULL,
    exploration_id uuid,
    provider varchar NOT NULL,
    return_path varchar NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    CONSTRAINT identity_oauth_states_state_hash_format_ck CHECK (state_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT identity_oauth_states_browser_nonce_hash_format_ck CHECK (
        browser_nonce_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT identity_oauth_states_return_path_ck CHECK (return_path LIKE '/%')
);

CREATE INDEX identity_oauth_states_guest_id_idx
    ON onmaru.identity_oauth_states (guest_id);
CREATE INDEX identity_oauth_states_exploration_id_idx
    ON onmaru.identity_oauth_states (exploration_id);
CREATE INDEX identity_oauth_states_expires_at_idx
    ON onmaru.identity_oauth_states (expires_at);

CREATE VIEW onmaru.identity_valid_oauth_states AS
SELECT state_hash,
       guest_id,
       browser_nonce_hash,
       exploration_id,
       provider,
       return_path,
       expires_at
FROM onmaru.identity_oauth_states
WHERE consumed_at IS NULL
  AND expires_at > CURRENT_TIMESTAMP;

CREATE TABLE onmaru.identity_exploration_grants (
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    exploration_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (member_id, exploration_id)
);

CREATE INDEX identity_exploration_grants_expires_at_idx
    ON onmaru.identity_exploration_grants (expires_at);
CREATE UNIQUE INDEX identity_exploration_grants_exploration_id_uq
    ON onmaru.identity_exploration_grants (exploration_id);

CREATE TABLE onmaru.identity_deletion_ledger (
    member_id uuid PRIMARY KEY REFERENCES onmaru.identity_members (id),
    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    status onmaru.identity_deletion_status NOT NULL,
    reason varchar NOT NULL
);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '003',
    'D03',
    76,
    'Member, OAuth identity, opaque session, guest grant, and deletion ledger schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
