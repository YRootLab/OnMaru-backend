-- onmaru-checksum: member-profiles-v039-20261003
-- Issue: #552 Privacy-first member profiles rendered by FE-owned assets.

CREATE TABLE onmaru.identity_member_profiles (
    member_id uuid PRIMARY KEY REFERENCES onmaru.identity_members (id) ON DELETE CASCADE,
    display_name varchar(20) NOT NULL,
    character_id varchar(20) NOT NULL,
    background_id varchar(20) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT identity_member_profiles_display_name_ck CHECK (
        display_name = btrim(display_name)
        AND char_length(display_name) BETWEEN 2 AND 20
    ),
    CONSTRAINT identity_member_profiles_character_id_ck CHECK (
        character_id ~ '^CHARACTER_(0[1-9]|10)$'
    ),
    CONSTRAINT identity_member_profiles_background_id_ck CHECK (
        background_id ~ '^BACKGROUND_(0[1-9]|10)$'
    ),
    CONSTRAINT identity_member_profiles_updated_at_ck CHECK (updated_at >= created_at)
);

WITH profile_hashes AS (
    SELECT
        member.id AS member_id,
        member.created_at,
        decode(md5(member.id::text), 'hex') AS digest
    FROM onmaru.identity_members member
)
INSERT INTO onmaru.identity_member_profiles (
    member_id,
    display_name,
    character_id,
    background_id,
    created_at,
    updated_at
)
SELECT
    member_id,
    (ARRAY[
        '고요한', '따뜻한', '느긋한', '정겨운', '포근한',
        '산뜻한', '다정한', '잔잔한', '맑은', '소박한'
    ])[1 + get_byte(digest, 0) % 10]
        || ' '
        || (ARRAY[
            '마루', '기와', '골목', '달빛', '솔바람',
            '꽃길', '한지', '찻잔', '구름', '나들이'
        ])[1 + get_byte(digest, 1) % 10]
        || ' '
        || lpad(((get_byte(digest, 2) * 256 + get_byte(digest, 3)) % 10000)::text, 4, '0'),
    'CHARACTER_' || lpad((1 + get_byte(digest, 4) % 10)::text, 2, '0'),
    'BACKGROUND_' || lpad((1 + get_byte(digest, 5) % 10)::text, 2, '0'),
    created_at,
    created_at
FROM profile_hashes;

COMMENT ON TABLE onmaru.identity_member_profiles IS
    'Current public member profile; FE owns CHARACTER/BACKGROUND assets and colors';
COMMENT ON COLUMN onmaru.identity_member_profiles.character_id IS
    'Stable FE asset slot CHARACTER_01 through CHARACTER_10; not an image URL';
COMMENT ON COLUMN onmaru.identity_member_profiles.background_id IS
    'Stable FE color slot BACKGROUND_01 through BACKGROUND_10; not a HEX value';

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '039', 'MEMBER_PROFILES', 552,
    'Privacy-first member display names and FE-owned character/background selections'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
