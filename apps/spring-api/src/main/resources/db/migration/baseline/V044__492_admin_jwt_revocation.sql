-- onmaru-checksum: admin-jwt-revocation-v044-20261006
-- Issue: #492 Persist admin access-token revocation state.

ALTER TABLE onmaru.identity_admin_accounts
    ADD COLUMN tokens_valid_after timestamptz NOT NULL DEFAULT '-infinity';

CREATE TABLE onmaru.identity_admin_access_token_revocations (
    jti_hash varchar(64) PRIMARY KEY,
    admin_id uuid NOT NULL REFERENCES onmaru.identity_admin_accounts (id),
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT identity_admin_access_token_revocations_hash_ck
        CHECK (jti_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX identity_admin_access_token_revocations_expires_at_idx
    ON onmaru.identity_admin_access_token_revocations (expires_at);

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '044',
    'ADMIN_JWT_REVOCATION',
    492,
    'Persist admin access-token revocations and account token validity boundary'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
