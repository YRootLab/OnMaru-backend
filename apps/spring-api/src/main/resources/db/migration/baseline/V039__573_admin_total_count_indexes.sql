-- onmaru-checksum: admin-total-count-indexes-v039-20261003
-- Issue: #573 Admin cursor list totalCount query indexes.

CREATE INDEX identity_members_admin_status_page_idx
    ON onmaru.identity_members (status, created_at DESC, id DESC);

CREATE INDEX community_visit_reviews_admin_status_page_idx
    ON onmaru.community_visit_reviews (status, created_at DESC, id DESC)
    WHERE public_place_id IS NOT NULL
      AND latitude IS NOT NULL
      AND longitude IS NOT NULL;

CREATE INDEX community_review_reports_admin_open_reason_page_idx
    ON onmaru.community_review_reports (reason, created_at DESC, id DESC)
    WHERE status = 'OPEN';

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '039',
    'ADMIN_TOTAL_COUNT_INDEXES',
    573,
    'Filter indexes for admin cursor page totalCount queries'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
