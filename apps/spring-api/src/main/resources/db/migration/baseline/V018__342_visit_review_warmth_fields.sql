-- V018 (#342): VisitReview에 온기 후기 표시 필드를 추가한다.
-- tags는 PostgreSQL JSONB 배열로 보관하며, 항목별 길이와 허용 mood/score는 애플리케이션 경계에서 검증한다.

ALTER TABLE onmaru.community_visit_reviews
    ADD COLUMN mood varchar(16),
    ADD COLUMN score smallint,
    ADD COLUMN tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD CONSTRAINT community_visit_reviews_mood_ck CHECK (
        mood IS NULL OR mood IN ('북적', '한적')
    ),
    ADD CONSTRAINT community_visit_reviews_score_ck CHECK (
        score IS NULL OR score BETWEEN 1 AND 5
    ),
    ADD CONSTRAINT community_visit_reviews_tags_array_ck CHECK (
        jsonb_typeof(tags) = 'array'
    );

INSERT INTO onmaru_registry.migration_version_reservations (
    version,
    reserved_for,
    issue_number,
    description
) VALUES (
    '018',
    '342',
    342,
    'VisitReview warmth fields: mood, score, and tags'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
