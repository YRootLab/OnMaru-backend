-- onmaru-checksum: hanok-stamp-book-v027-20260926
-- Issue: #262 Server-owned, location-verified Hanok stamp book.

CREATE TYPE onmaru.stamp_rarity AS ENUM ('COMMON', 'REGIONAL', 'RARE', 'LEGENDARY');
CREATE TYPE onmaru.stamp_condition_type AS ENUM ('REGION_VISIT', 'NIGHT_VISIT', 'REGION_COUNT');

CREATE TABLE onmaru.stamp_definitions (
    code varchar PRIMARY KEY,
    name varchar NOT NULL,
    description text NOT NULL,
    condition_label varchar NOT NULL,
    seal_text varchar NOT NULL,
    icon_name varchar NOT NULL,
    color varchar NOT NULL,
    rarity onmaru.stamp_rarity NOT NULL,
    condition_type onmaru.stamp_condition_type NOT NULL,
    required_count smallint,
    region_group varchar,
    sort_order smallint NOT NULL UNIQUE,
    active boolean NOT NULL DEFAULT true,
    CONSTRAINT stamp_definitions_code_ck CHECK (code ~ '^stamp_[a-z0-9_]+$'),
    CONSTRAINT stamp_definitions_text_ck CHECK (
        btrim(name) <> '' AND btrim(description) <> '' AND btrim(condition_label) <> ''
        AND btrim(seal_text) <> '' AND btrim(icon_name) <> ''
    ),
    CONSTRAINT stamp_definitions_color_ck CHECK (color ~ '^#[0-9a-fA-F]{6}$'),
    CONSTRAINT stamp_definitions_required_count_ck CHECK (
        (condition_type = 'REGION_COUNT' AND required_count > 0 AND region_group IS NULL)
        OR (condition_type <> 'REGION_COUNT' AND required_count IS NULL)
    ),
    CONSTRAINT stamp_definitions_region_group_ck CHECK (
        region_group IS NULL OR region_group ~ '^[A-Z_]+$'
    )
);

CREATE TABLE onmaru.stamp_region_rules (
    stamp_code varchar NOT NULL REFERENCES onmaru.stamp_definitions (code),
    region_code varchar NOT NULL,
    PRIMARY KEY (stamp_code, region_code),
    CONSTRAINT stamp_region_rules_code_ck CHECK (region_code ~ '^kr-[a-z0-9]+(?:-[a-z0-9]+)*$')
);

CREATE INDEX stamp_region_rules_region_code_idx
    ON onmaru.stamp_region_rules (region_code, stamp_code);

CREATE TABLE onmaru.stamp_check_ins (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id) ON DELETE CASCADE,
    place_id uuid NOT NULL REFERENCES onmaru.catalog_place_identity (id),
    public_place_id varchar NOT NULL,
    region_code varchar NOT NULL,
    checked_in_at timestamptz NOT NULL,
    check_in_bucket timestamptz NOT NULL,
    distance_meters integer NOT NULL,
    accuracy_meters integer NOT NULL,
    CONSTRAINT stamp_check_ins_member_place_bucket_uq UNIQUE (member_id, place_id, check_in_bucket),
    CONSTRAINT stamp_check_ins_member_id_uq UNIQUE (member_id, id),
    CONSTRAINT stamp_check_ins_public_place_id_ck CHECK (public_place_id ~ '^p-[a-z0-9]+(-[a-z0-9]+)*$'),
    CONSTRAINT stamp_check_ins_region_code_ck CHECK (region_code ~ '^kr-[a-z0-9]+(?:-[a-z0-9]+)*$'),
    CONSTRAINT stamp_check_ins_distance_ck CHECK (distance_meters >= 0 AND distance_meters <= 300),
    CONSTRAINT stamp_check_ins_accuracy_ck CHECK (accuracy_meters > 0 AND accuracy_meters <= 100),
    CONSTRAINT stamp_check_ins_verified_radius_ck CHECK (distance_meters - accuracy_meters <= 200),
    CONSTRAINT stamp_check_ins_bucket_ck CHECK (
        check_in_bucket = date_bin('15 minutes', checked_in_at, '2000-01-01 00:00:00+00'::timestamptz)
    )
);

CREATE INDEX stamp_check_ins_member_checked_idx
    ON onmaru.stamp_check_ins (member_id, checked_in_at DESC, id DESC);

CREATE TABLE onmaru.stamp_awards (
    id uuid PRIMARY KEY,
    member_id uuid NOT NULL REFERENCES onmaru.identity_members (id) ON DELETE CASCADE,
    stamp_code varchar NOT NULL REFERENCES onmaru.stamp_definitions (code),
    trigger_check_in_id uuid NOT NULL,
    awarded_at timestamptz NOT NULL,
    CONSTRAINT stamp_awards_member_stamp_uq UNIQUE (member_id, stamp_code),
    CONSTRAINT stamp_awards_check_in_fk FOREIGN KEY (member_id, trigger_check_in_id)
        REFERENCES onmaru.stamp_check_ins (member_id, id) ON DELETE CASCADE
);

CREATE INDEX stamp_awards_member_awarded_idx
    ON onmaru.stamp_awards (member_id, awarded_at DESC, id DESC);

INSERT INTO onmaru.stamp_definitions (
    code, name, description, condition_label, seal_text, icon_name, color, rarity,
    condition_type, required_count, region_group, sort_order
) VALUES
('stamp_bukchon', '북촌 한옥마을 인장', '조선 왕실과 고관대작들의 숨결이 깃든 600년 역사의 북촌 한옥 지구를 유람하다.', '북촌·인사동 일대 한옥 방문', '北村', 'Landmark', '#b91c1c', 'COMMON', 'REGION_VISIT', NULL, 'SEOUL', 1),
('stamp_eunpyeong', '은평 북한산 고요', '북한산 자락 아래 가지런한 처마선과 맑은 공기가 감도는 현대 한옥 마을을 유람하다.', '은평 한옥마을 일대 방문', '恩平', 'Mountain', '#0f766e', 'COMMON', 'REGION_VISIT', NULL, 'SEOUL', 2),
('stamp_hwaseong', '수원 화성 행궁첩', '정조대왕의 효심과 개혁 정신이 어린 수원 행궁동의 유려한 전통 누각을 거닐다.', '수원 화성 및 행궁동 한옥 방문', '華城', 'Castle', '#b45309', 'COMMON', 'REGION_VISIT', NULL, 'GYEONGGI', 3),
('stamp_gangneung', '강릉 선교장 만석루', '신사임당과 율곡의 학문, 그리고 조선 사대부 대저택 선교장의 품격을 맛보다.', '강릉 선교장 및 오죽헌 일대 방문', '船橋', 'Building', '#1e40af', 'REGIONAL', 'REGION_VISIT', NULL, 'GANGWON', 4),
('stamp_oeam', '아산 외암 돌담길', '돌담길 너머 초가와 기와가 빚어낸 고즈넉한 선비 마을의 정취를 누리다.', '아산 외암민속마을 일대 방문', '巍岩', 'Compass', '#4338ca', 'COMMON', 'REGION_VISIT', NULL, 'CHUNGCHEONG', 5),
('stamp_jeonju', '전주 경기전 태조인', '태조 이성계의 어진을 모신 경기전과 기와지붕이 파도치는 전주를 탐방하다.', '전주 한옥마을 일대 방문', '全州', 'Crown', '#991b1b', 'REGIONAL', 'REGION_VISIT', NULL, 'JEOLLA', 6),
('stamp_unjoru', '지리산 구례 운조루', '타인능해의 쌀독 나눔 철학을 간직한 지리산 명당 삼이당의 숨결을 느끼다.', '구례 운조루 및 지리산 일대 한옥 방문', '雲鳥', 'HeartHandshake', '#065f46', 'RARE', 'REGION_VISIT', NULL, 'JEOLLA', 7),
('stamp_andong', '안동 하회 부용대', '낙동강이 감싸 흐르는 유교 문화의 본향, 하회마을의 충절을 기리다.', '안동 하회마을 및 도산서원 방문', '河回', 'Scroll', '#831843', 'REGIONAL', 'REGION_VISIT', NULL, 'GYEONGSANG', 8),
('stamp_yangdong', '경주 양동 유네스코', '두 가문이 500년 넘게 일군 세계유산 양동마을의 기품을 느끼다.', '경주 양동마을 및 교촌 한옥마을 방문', '良洞', 'Sparkles', '#7c2d12', 'REGIONAL', 'REGION_VISIT', NULL, 'GYEONGSANG', 9),
('stamp_jeju_seongup', '탐라 성읍 흙담집', '제주 현무암 돌담과 새지붕 전통 가옥을 체험하다.', '제주 성읍민속마을 일대 방문', '城邑', 'Palmtree', '#15803d', 'REGIONAL', 'REGION_VISIT', NULL, 'JEJU', 10),
('stamp_night_hanok', '달빛 고택 야행인', '은은한 달빛이 처마 끝에 내려앉는 고요한 밤의 정취를 담다.', '오후 6시 이후 또는 오전 6시 이전 한옥 명소 방문', '夜景', 'Moon', '#6d28d9', 'RARE', 'NIGHT_VISIT', NULL, NULL, 11),
('stamp_national_master', '팔도 유람 팔도어보', '삼천리 강산의 한옥을 두루 유람한 온마루 풍류객의 수결이다.', '서로 다른 5개 권역 한옥 방문', '八道', 'Trophy', '#d4af37', 'LEGENDARY', 'REGION_COUNT', 5, NULL, 12);

INSERT INTO onmaru.stamp_region_rules (stamp_code, region_code) VALUES
('stamp_bukchon', 'kr-11-jongno'),
('stamp_eunpyeong', 'kr-11-eunpyeong'),
('stamp_hwaseong', 'kr-41-suwon'),
('stamp_gangneung', 'kr-42-gangneung'),
('stamp_oeam', 'kr-44-asan'),
('stamp_jeonju', 'kr-45-jeonju'),
('stamp_unjoru', 'kr-46-gurye'),
('stamp_andong', 'kr-47-andong'),
('stamp_yangdong', 'kr-47-gyeongju'),
('stamp_jeju_seongup', 'kr-50-seogwipo');

INSERT INTO onmaru_registry.migration_version_reservations (
    version, reserved_for, issue_number, description
) VALUES (
    '027', 'HANOK_STAMP_BOOK', 262,
    'Location-verified member check-ins and relational Hanok stamp awards'
) ON CONFLICT (version) DO UPDATE
SET reserved_for = EXCLUDED.reserved_for,
    issue_number = EXCLUDED.issue_number,
    description = EXCLUDED.description;
