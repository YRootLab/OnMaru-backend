# Persistence & Entity Model Specification (RDBMS / Cache)

> **문서 ID**: `BE-SPEC-PERSISTENCE-001`  
> **Target Engine**: PostgreSQL with PostGIS / Redis  
> **Status**: Proposal based on FE Implementation Evidence  
> **Source Domains**: `src/hanok`, `src/map`, `src/features/odii-audio`

---

## 1. Physical Table Schema Candidates

### 1.1 `places` (통합 장소 테이블)
```sql
CREATE TABLE places (
    id VARCHAR(64) PRIMARY KEY,                    -- 자체 UUID 또는 TourAPI contentId
    source_content_id VARCHAR(32) UNIQUE,          -- TourAPI contentid (중복 수집 방지)
    source_type VARCHAR(16) NOT NULL DEFAULT 'TourAPI', -- 'TourAPI' | 'Internal'
    name VARCHAR(255) NOT NULL,                    -- 장소 명칭
    category VARCHAR(32) NOT NULL,                 -- 'HERITAGE' | 'STAY' | 'CULTURE' | 'FOOD' | 'CAFE'
    latitude NUMERIC(10, 7),                       -- 위도 (WGS84 33.0 ~ 39.0)
    longitude NUMERIC(10, 7),                      -- 경도 (WGS84 124.0 ~ 132.0)
    location GEOGRAPHY(Point, 4326),               -- PostGIS 공간 데이터 (반경 검색 최적화)
    region VARCHAR(32) NOT NULL,                   -- 17개 표준 시/도 권역
    address VARCHAR(255) NOT NULL,                 -- 도로명 / 지번 주소
    telephone VARCHAR(64),                         -- 대표 전화번호
    thumbnail_url TEXT,                            -- 대표 이미지 HTTPS URL
    has_image BOOLEAN NOT NULL DEFAULT FALSE,
    is_traditional BOOLEAN NOT NULL DEFAULT TRUE,  -- 정통 한옥/문화재 여부
    overview TEXT,                                 -- 상세 개요
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 공간 검색 인덱스
CREATE INDEX idx_places_location ON places USING GIST (location);
-- 복합 필터 인덱스
CREATE INDEX idx_places_region_category ON places (region, category);
```

### 1.2 `hanok_heritages` (한옥 문화재/아카이브 확장 테이블)
```sql
CREATE TABLE hanok_heritages (
    place_id VARCHAR(64) PRIMARY KEY REFERENCES places(id) ON DELETE CASCADE,
    heritage_type VARCHAR(64) NOT NULL,            -- '도심형' | '집성촌형' | '사대부 고택' | '궁궐 한옥' 등
    badges JSONB NOT NULL DEFAULT '[]'::jsonb,     -- ["세계유산", "국가지정", "고택"]
    monthly_curation_month VARCHAR(7),             -- '2026-09' (이달의 한옥 선정 월)
    architectural_features TEXT,                   -- 건축적 양식 설명
    operating_hours TEXT,                          -- 이용시간
    rest_date TEXT,                                -- 휴무일
    parking_info TEXT                              -- 주차 가능 여부
);
```

### 1.3 `warmth_reviews` (1줄 온기 및 정취 후기 테이블)
```sql
CREATE TABLE warmth_reviews (
    id VARCHAR(64) PRIMARY KEY,                    -- UUID
    place_id VARCHAR(64) NOT NULL REFERENCES places(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,                  -- 작성자 식별자 (Guest Session ID 또는 회원 ID)
    crowd_mood VARCHAR(16) NOT NULL,               -- '북적' | '한적'
    rating_score SMALLINT CHECK (rating_score BETWEEN 1 AND 5),
    text_content VARCHAR(200) NOT NULL,            -- 1줄 온기 본문
    tags JSONB NOT NULL DEFAULT '[]'::jsonb,       -- ["고즈넉함", "비오는날"]
    helpful_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_warmth_place_created ON warmth_reviews (place_id, created_at DESC);
```

### 1.4 `odii_stories` (오디 오디오 투어 및 자막 테이블)
```sql
CREATE TABLE odii_stories (
    id VARCHAR(64) PRIMARY KEY,                    -- TourAPI stid
    place_id VARCHAR(64) REFERENCES places(id) ON DELETE SET NULL,
    title VARCHAR(255) NOT NULL,                   -- 장소 명칭
    audio_title VARCHAR(255) NOT NULL,             -- 오디오 트랙 제목
    category VARCHAR(64) NOT NULL,                 -- '한옥/고택', '궁궐/역사' 등
    speaker_name VARCHAR(64),                      -- 도슨트 이름
    audio_url TEXT NOT NULL,                       -- CDN 스트리밍 URL
    play_time_seconds INT NOT NULL,                -- 재생 시간(초)
    raw_script TEXT NOT NULL,                      -- 원본 대본 텍스트
    parsed_subtitles JSONB NOT NULL DEFAULT '[]'::jsonb, -- [{ "id": 0, "timeSec": 0.0, "text": "..." }]
    created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

---

## 2. Redis Caching Architecture

| Cache Key Pattern | Data Structure | TTL | Purpose |
| :--- | :--- | :--- | :--- |
| `cache:hanok:list:{region}:{type}` | String (JSON) | 1 hour | 한옥 도감 필터링 목록 캐싱 |
| `cache:hanok:detail:{placeId}` | String (JSON) | 24 hours | 한옥 상세 모달 응답 캐싱 |
| `cache:map:congestion:{region}:{date}` | String (JSON) | 10 mins | 한국관광 데이터랩 혼잡도 시계열 캐싱 |
| `cache:odii:stories:{category}` | String (JSON) | 6 hours | 오디 오디오 목록 캐싱 |
