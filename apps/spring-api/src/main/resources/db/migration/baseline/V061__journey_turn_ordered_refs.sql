-- onmaru-checksum: journey-turn-ordered-refs-v061-20261126
-- Keep the public place refs of each completed turn so transcripts can rebuild place cards per turn.
-- Boards are overwritten per run, so per-turn refs are the only durable source for older turns.

ALTER TABLE onmaru.discovery_turns
    ADD COLUMN IF NOT EXISTS ordered_refs jsonb,
    ADD COLUMN IF NOT EXISTS place_reasons jsonb,
    ADD COLUMN IF NOT EXISTS result_reason varchar;

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '061', 'JOURNEY_TURN_PLACE_CARDS', 884,
    'Per-turn place refs, place reasons, and AI result reason for turn transcripts'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
