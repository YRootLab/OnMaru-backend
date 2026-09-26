-- onmaru-checksum: hanok-stamp-identity-v030-20260927
-- Issue: #262 Persist OAuth PKCE verification material for the production JDBC identity store.

ALTER TABLE onmaru.identity_oauth_states
    ADD COLUMN pkce_verifier_hash varchar;

UPDATE onmaru.identity_oauth_states
SET pkce_verifier_hash = browser_nonce_hash
WHERE pkce_verifier_hash IS NULL;

ALTER TABLE onmaru.identity_oauth_states
    ALTER COLUMN pkce_verifier_hash SET NOT NULL,
    ADD CONSTRAINT identity_oauth_states_pkce_verifier_hash_format_ck CHECK (
        pkce_verifier_hash ~ '^[0-9a-f]{64}$'
    );

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '030', 'HANOK_STAMP_IDENTITY', 262,
    'Production JDBC identity store PKCE verification persistence'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
