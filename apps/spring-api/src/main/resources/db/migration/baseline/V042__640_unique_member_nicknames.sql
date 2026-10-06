-- onmaru-checksum: unique-member-nicknames-v042-20261006
-- Issue: #640 Authenticated nickname availability and uniqueness.

LOCK TABLE onmaru.identity_member_profiles IN SHARE ROW EXCLUSIVE MODE;

DO $$
DECLARE
    duplicate_profile record;
    candidate text;
    candidate_attempt integer;
    repaired boolean;
BEGIN
    FOR duplicate_profile IN
        SELECT member_id
        FROM (
            SELECT
                member_id,
                row_number() OVER (
                    PARTITION BY display_name
                    ORDER BY created_at, member_id
                ) AS duplicate_order
            FROM onmaru.identity_member_profiles
        ) ranked_profiles
        WHERE duplicate_order > 1
        ORDER BY member_id
    LOOP
        repaired := false;
        FOR candidate_attempt IN 0..999 LOOP
            candidate := '익명-' || substring(
                md5(duplicate_profile.member_id::text || ':' || candidate_attempt::text),
                1,
                17
            );
            IF NOT EXISTS (
                SELECT 1
                FROM onmaru.identity_member_profiles profile
                WHERE profile.display_name = candidate
                  AND profile.member_id <> duplicate_profile.member_id
            ) THEN
                UPDATE onmaru.identity_member_profiles
                SET display_name = candidate,
                    updated_at = GREATEST(updated_at, CURRENT_TIMESTAMP)
                WHERE member_id = duplicate_profile.member_id;
                repaired := true;
                EXIT;
            END IF;
        END LOOP;

        IF NOT repaired THEN
            RAISE EXCEPTION
                'Unable to allocate a unique fallback nickname for member %',
                duplicate_profile.member_id;
        END IF;
    END LOOP;
END
$$;

ALTER TABLE onmaru.identity_member_profiles
    ADD CONSTRAINT identity_member_profiles_display_name_uq UNIQUE (display_name);

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '042', 'UNIQUE_MEMBER_NICKNAMES', 640,
    'Unique member nicknames with deterministic repair of historical duplicates'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
