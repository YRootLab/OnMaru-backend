-- onmaru-checksum: hanok-stamp-ranking-v029-20260927
-- Issue: #262 Opt-in anonymous Hanok stamp ranking profiles.

CREATE TABLE onmaru.stamp_ranking_profiles (
    member_id uuid PRIMARY KEY REFERENCES onmaru.identity_members (id) ON DELETE CASCADE,
    ranking_public_id uuid,
    public_nickname varchar(20),
    nickname_normalized varchar(20),
    nickname_type varchar,
    participating boolean NOT NULL DEFAULT false,
    consented_at timestamptz,
    withdrawn_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT stamp_ranking_profiles_state_ck CHECK (
        (participating AND ranking_public_id IS NOT NULL
          AND public_nickname IS NOT NULL AND nickname_normalized IS NOT NULL
          AND nickname_type IS NOT NULL AND nickname_type = 'GENERATED'
          AND consented_at IS NOT NULL AND withdrawn_at IS NULL)
        OR
        (NOT participating AND ranking_public_id IS NULL
          AND public_nickname IS NULL AND nickname_normalized IS NULL
          AND nickname_type IS NULL)
    ),
    CONSTRAINT stamp_ranking_profiles_nickname_ck CHECK (
        public_nickname IS NULL OR (
            public_nickname = btrim(public_nickname)
            AND char_length(public_nickname) BETWEEN 2 AND 20
        )
    )
);

CREATE UNIQUE INDEX stamp_ranking_profiles_public_id_uq
    ON onmaru.stamp_ranking_profiles (ranking_public_id)
    WHERE participating;

CREATE UNIQUE INDEX stamp_ranking_profiles_nickname_uq
    ON onmaru.stamp_ranking_profiles (nickname_normalized)
    WHERE participating;

CREATE INDEX stamp_ranking_profiles_participating_idx
    ON onmaru.stamp_ranking_profiles (member_id)
    WHERE participating;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '029', 'HANOK_STAMP_RANKING', 262,
    'Opt-in anonymous Hanok stamp ranking profiles'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
