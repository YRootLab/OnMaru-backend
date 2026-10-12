-- onmaru-checksum: kcontents-official-work-research-v051-20261012
-- Issue #718. Extend internal research batches; public relations remain evidence-gated.

ALTER TABLE onmaru.k_content_label_batches
    ADD COLUMN official_work_sha256 char(64)
        CHECK (official_work_sha256 ~ '^[0-9a-f]{64}$');

ALTER TABLE onmaru.k_content_candidate_labels
    ADD CONSTRAINT k_content_candidate_labels_official_work_check
        CHECK (label->'officialWorkResearch' IS NULL OR
               ((jsonb_typeof(label->'officialWorkResearch') = 'object') IS TRUE AND
                (label->'officialWorkResearch'->>'contentId' = content_id) IS TRUE));

INSERT INTO onmaru_registry.migration_version_reservations (version,reserved_for,issue_number,description)
VALUES ('051','KCONTENTS_OFFICIAL_WORK_RESEARCH',718,'Official work research digest on full candidate label batches')
ON CONFLICT (version) DO UPDATE SET reserved_for=EXCLUDED.reserved_for,
    issue_number=EXCLUDED.issue_number, description=EXCLUDED.description;
