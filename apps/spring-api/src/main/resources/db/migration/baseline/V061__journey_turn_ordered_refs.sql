-- onmaru-checksum: journey-turn-ordered-refs-v061-20261126
-- Keep the public place refs of each completed turn so transcripts can rebuild place cards per turn.
-- Boards are overwritten per run, so per-turn refs are the only durable source for older turns.

ALTER TABLE onmaru.discovery_turns
    ADD COLUMN IF NOT EXISTS ordered_refs jsonb,
    ADD COLUMN IF NOT EXISTS place_reasons jsonb,
    ADD COLUMN IF NOT EXISTS result_reason varchar;
