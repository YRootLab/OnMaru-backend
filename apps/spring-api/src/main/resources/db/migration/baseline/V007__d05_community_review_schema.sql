-- onmaru-checksum: d05-v007-20260915
-- Issue: #78 D05 VisitReview, like, report, and moderation schema.

CREATE TYPE onmaru.community_review_status AS ENUM (
    'PUBLISHED',
    'HIDDEN',
    'REMOVED',
    'DELETED'
);

CREATE TYPE onmaru.community_report_status AS ENUM (
    'OPEN',
    'RESOLVED',
    'DISMISSED'
);

CREATE TABLE onmaru.community_visit_reviews (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    text text NOT NULL,
    status onmaru.community_review_status NOT NULL,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT community_visit_reviews_text_ck CHECK (btrim(text) <> ''),
    CONSTRAINT community_visit_reviews_deleted_at_ck CHECK (
        status <> 'DELETED' OR deleted_at IS NOT NULL
    )
);

CREATE INDEX community_visit_reviews_created_at_idx
    ON onmaru.community_visit_reviews (created_at, id);
CREATE INDEX community_visit_reviews_place_created_at_idx
    ON onmaru.community_visit_reviews (place_id, created_at, id);
CREATE INDEX community_visit_reviews_member_id_idx
    ON onmaru.community_visit_reviews (member_id);

CREATE TABLE onmaru.community_review_likes (
    review_id uuid NOT NULL REFERENCES onmaru.community_visit_reviews (id),
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    created_at timestamptz NOT NULL,
    PRIMARY KEY (review_id, member_id)
);

CREATE INDEX community_review_likes_member_id_idx
    ON onmaru.community_review_likes (member_id);

CREATE TABLE onmaru.community_review_reports (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES onmaru.community_visit_reviews (id),
    reporter_member_id uuid NOT NULL REFERENCES onmaru.identity_members (id),
    reason varchar NOT NULL,
    detail text,
    status onmaru.community_report_status NOT NULL,
    created_at timestamptz NOT NULL,
    resolved_at timestamptz,
    CONSTRAINT community_review_reports_reason_ck CHECK (
        reason IN ('SPAM', 'ABUSE', 'PERSONAL_DATA', 'COPYRIGHT', 'OTHER')
    ),
    CONSTRAINT community_review_reports_resolved_at_ck CHECK (
        (status = 'OPEN' AND resolved_at IS NULL)
        OR (status IN ('RESOLVED', 'DISMISSED') AND resolved_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX community_review_reports_open_uq
    ON onmaru.community_review_reports (review_id, reporter_member_id)
    WHERE status = 'OPEN';
CREATE INDEX community_review_reports_status_created_at_idx
    ON onmaru.community_review_reports (status, created_at);

CREATE OR REPLACE FUNCTION onmaru.community_reject_self_report()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM onmaru.community_visit_reviews review
        WHERE review.id = NEW.review_id
          AND review.member_id = NEW.reporter_member_id
    ) THEN
        RAISE EXCEPTION 'community_review_reports_not_self_ck: member cannot report own review'
            USING ERRCODE = '23514',
                  CONSTRAINT = 'community_review_reports_not_self_ck';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER community_review_reports_not_self_trg
    BEFORE INSERT OR UPDATE OF review_id, reporter_member_id
    ON onmaru.community_review_reports
    FOR EACH ROW
    EXECUTE FUNCTION onmaru.community_reject_self_report();

CREATE TABLE onmaru.community_review_moderation_actions (
    id uuid PRIMARY KEY,
    review_id uuid NOT NULL REFERENCES onmaru.community_visit_reviews (id),
    actor_type varchar NOT NULL,
    actor_ref varchar,
    previous_status onmaru.community_review_status NOT NULL,
    next_status onmaru.community_review_status NOT NULL,
    reason varchar NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT community_review_moderation_actions_actor_type_ck CHECK (
        actor_type IN ('SYSTEM', 'OPERATOR')
    ),
    CONSTRAINT community_review_moderation_actions_reason_ck CHECK (btrim(reason) <> ''),
    CONSTRAINT community_review_moderation_actions_status_change_ck CHECK (
        previous_status <> next_status
    )
);

CREATE INDEX community_review_moderation_actions_review_created_at_idx
    ON onmaru.community_review_moderation_actions (review_id, created_at);

CREATE OR REPLACE FUNCTION onmaru.community_moderation_actions_append_only()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'community moderation actions are append-only'
        USING ERRCODE = '55000',
              CONSTRAINT = 'community_review_moderation_actions_append_only_ck';
END
$$;

CREATE TRIGGER community_review_moderation_actions_append_only_trg
    BEFORE UPDATE OR DELETE
    ON onmaru.community_review_moderation_actions
    FOR EACH ROW
    EXECUTE FUNCTION onmaru.community_moderation_actions_append_only();

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '007',
    'D05',
    78,
    'Visit review, like, report, and moderation audit schema'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
