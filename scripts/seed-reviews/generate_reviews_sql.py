import json
import random
import uuid
import datetime

with open("scripts/seed-reviews/places.json", "r", encoding="utf-8") as f:
    places = json.load(f)

print(f"Loaded {len(places)} places")

# 선별 기준: 랜드마크 우선 필터링 (북촌, 남산, 전주, 안동, 강릉, 경주, 서촌, 은평 등 대표 명소 우선)
# 대표 키워드가 들어간 곳 우선
def get_priority(p):
    name = p['name']
    addr = p.get('address', '')
    for landmark in ['북촌', '남산', '전주', '하회', '양동', '선교장', '황리단', '오죽', '은평', '서촌', '인사동', '교동', '임청각', '농암']:
        if landmark in name or landmark in addr:
            return 1
    return 2

places.sort(key=get_priority)

# 80~100개 주요 장소 선별
selected_places = places[:85]
print(f"Selected {len(selected_places)} major places")

# 가상 유저 풀 생성 (35명)
PREFIXES = ['고요한', '따뜻한', '느긋한', '정겨운', '포근한', '산뜻한', '다정한', '잔잔한', '맑은', '소박한', '바람부는', '햇살가득', '달빛어린', '별빛담은', '솔향기', '단아한', '온기품은']
NOUNS = ['마루', '기와', '골목', '달빛', '솔바람', '꽃길', '한지', '찻잔', '구름', '나들이', '툇마루', '담장', '처마', '연못', '풍경소리']

random.seed(42) # 재현 가능하도록 시드 고정

users = []
for i in range(35):
    member_id = f"54500700-0000-4000-8000-{i+1:012d}"
    nickname = f"{random.choice(PREFIXES)} {random.choice(NOUNS)} {random.randint(10, 999)}"
    char_id = f"CHARACTER_{random.randint(1, 10):02d}"
    bg_id = f"BACKGROUND_{random.randint(1, 10):02d}"
    created_days_ago = random.randint(15, 40)
    user_created_at = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=created_days_ago)
    users.append({
        'id': member_id,
        'nickname': nickname,
        'character_id': char_id,
        'background_id': bg_id,
        'created_at': user_created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

# 관광지 유형별/분위기별 리얼 리뷰 템플릿 풀
# (현실적이고 위트있거나 생생한 경험담 위주, AI 느낌 제거)
TEMPLATES = [
    # 1. 사진/인생샷 & 주말 북적
    {
        "mood": "북적",
        "tags": ["사진맛집", "데이트", "주말나들이"],
        "score": 5,
        "texts": [
            "주말 낮에 오니까 사람 진짜 많은데 골목 사이로 한복 입고 사진 찍는 분들 많아서 분위기 활기차고 좋아요!",
            "주말이라 북적거리긴 한데 처마 밑 햇살 들어올 때 사진 찍으면 인생샷 무조건 건집니다 ㅎㅎ",
            "생각보다 사람 많아서 놀랐는데 구석구석 포토존이 진짜 많네요. 오후 4시쯤 노을 질 때 강추!",
            "골목마다 아기자기한 볼거리가 넘침. 사람 많아도 이 맛에 오는 거 아니겠습니까? 주말엔 활기차서 더 좋아요.",
            "주말에 친구들이랑 한복 대여해서 갔는데 골목길 채광이 사기예요 ㅋㅋㅋ 사람 많아도 사진은 다 건졌음!"
        ]
    },
    # 2. 평일 고즈넉 & 힐링 한적
    {
        "mood": "한적",
        "tags": ["고즈넉", "힐링", "혼자여행", "조용한"],
        "score": 5,
        "texts": [
            "일부러 연차 쓰고 평일 오전에 왔더니 진짜 조용하고 평화롭네요. 마루에 가만히 앉아만 있어도 힐링됨.",
            "바람 불 때마다 풍경 소리 딸랑거리는 게 너무 좋았어요. 머릿속 복잡할 때 혼자 산책 오기 딱입니다.",
            "주말엔 사람 많다던데 평일 늦은 오후는 완전 다른 세상이네요. 고즈넉한 한옥 정취 제대로 느끼고 갑니다.",
            "혼자 조용히 사색하기에 이만한 곳이 없어요. 마루 끝에 걸터앉아서 새소리 듣는 시간이 제일 행복했음.",
            "아침 일찍 개방 시간 맞춰서 갔더니 발소리만 사박사박 들려요. 복잡한 도시 벗어나서 힐링 제대로 하고 옴."
        ]
    },
    # 3. 비 오는 날 감성 (독특한 매력)
    {
        "mood": "한적",
        "tags": ["비오는날", "고즈넉", "비멍", "분위기맛집"],
        "score": 5,
        "texts": [
            "마침 비가 부슬부슬 내리는 날 갔는데 처마에서 빗방울 떨어지는 소리가 예술입니다... 비멍 때리다 시간 다 감.",
            "비 오는 날 한옥은 진짜 치트키네요. 흙냄새랑 젖은 나무 냄새가 묘하게 마음을 편하게 해줘요.",
            "비 와서 갈까 말까 고민했는데 안 왔으면 후회할 뻔! 빗소리 들으면서 마루에 앉아있으니 운치 최고예요.",
            "흐린 날씨라 사람도 별로 없고 운치 대박이었어요. 따뜻한 차 한 잔 마시면서 밖 바라보는데 영화 속 한 장면 같음."
        ]
    },
    # 4. 부모님/가족 동반 & 고택의 역사적 디테일
    {
        "mood": "한적",
        "tags": ["부모님과", "가족여행", "역사체험", "고택"],
        "score": 5,
        "texts": [
            "부모님 모시고 효도 관광으로 다녀왔는데 두 분 다 너무 좋아하셔서 뿌듯했습니다. 나무 기둥 결 하나하나 멋스러워요.",
            "대청마루랑 마당 구조가 옛 선조들 지혜가 고스란히 묻어있네요. 어르신들 모시고 오기 정말 훌륭한 장소입니다.",
            "해설사 선생님 설명 들으면서 둘러보니까 그냥 지나칠 뻔한 기와 문양이나 디테일들이 다 다르게 보여요. 꼭 설명 들어보세요!",
            "아이들이랑 같이 방문했는데 책에서만 보던 전통 가옥 직접 만져보고 걸어볼 수 있어서 교육적으로도 참 유익했네요."
        ]
    },
    # 5. 솔직 & 소소한 팁 (현실 꿀팁이 섞인 톡 튀는 리뷰)
    {
        "mood": "북적",
        "tags": ["방문팁", "데이트", "산책"],
        "score": 4,
        "texts": [
            "주차는 근처 공영주차장 이용하는 게 정신건강에 좋습니다 ㅋㅋㅋ 골목 안쪽은 차 절대 못 들어가요! 그래도 뷰는 최고.",
            "계단이나 언덕길이 조금 있어서 편한 운동화 필수입니다! 구두 신고 왔다가 발 아팠지만 풍경 보면서 극복함.",
            "낮 12시~2시는 햇빛이 꽤 강하니까 양산이나 모자 챙기세요. 해 질 녘 골목에 가로등 켜질 때가 진짜 낭만임.",
            "인기 포토존은 줄 좀 서야 하지만 회전율은 빨라요. 마당 구석 정자 쪽이 숨은 포토스팟입니다 다들 잘 모르더라구요!",
            "여름엔 모기기피제 살짝 챙기면 좋음 ㅎㅎ 그래도 마루에 불어오는 바람이 에어컨 바람보다 훨씬 상쾌하네요."
        ]
    },
    # 6. 주변 카페/차 한잔 & 먹거리 연계
    {
        "mood": "북적",
        "tags": ["전통차", "카페투어", "데이트", "먹거리"],
        "score": 5,
        "texts": [
            "산책 한 바퀴 돌고 바로 근처 한옥 찻집에서 오미자차 한 잔 마시니까 코스가 딱 완벽하네요. 주말 데이트 강추!",
            "골목길 따라 걷다가 구수한 냄새에 홀려서 전통 간식 사 먹었는데 꿀맛... 걷는 재미, 먹는 재미 다 잡을 수 있어요.",
            "주변 한옥 카페들이랑 묶어서 하루 코스로 다녀오기 정말 좋아요. 사진 백 장 넘게 찍고 갑니다 ㅋㅋㅋ",
            "동네 전체가 아기자기해서 발길 닿는 대로 들어가 본 작은 공방도 매력 있었어요. 기념품 하나씩 사 들고 옴."
        ]
    },
    # 7. 계절감 & 자연 풍경
    {
        "mood": "한적",
        "tags": ["풍경맛집", "계절감", "산책로", "고즈넉"],
        "score": 5,
        "texts": [
            "마당에 심어진 소나무랑 담장 너머 하늘 조화가 기가 막힙니다. 가을 단풍 들 때 무조건 다시 올 거예요.",
            "처마 끝 곡선 뒤로 푸른 산이 병풍처럼 둘러싸고 있는 풍경이 압권입니다. 눈이 맑아지는 기분이에요.",
            "초록초록한 마당 정원이랑 한옥 색감이 너무 잘 어울려요. 툇마루에서 바람 쐬고 있으면 잡생각이 다 날아갑니다."
        ]
    }
]

# 총 300개 생성
REVIEWS_COUNT = 300
reviews = []

# 타임스탬프: 최근 14일 전부터 오늘(몇 시간 전)까지 분산
now = datetime.datetime.now(datetime.timezone.utc)

for i in range(REVIEWS_COUNT):
    rev_id = f"54500700-0000-4000-9000-{i+1:012d}"
    user = random.choice(users)
    # 장소 선택 (selected_places 중 순환 및 약간의 가중)
    place = selected_places[i % len(selected_places)]
    
    # 템플릿 카테고리 선택
    tpl_group = random.choice(TEMPLATES)
    text_content = random.choice(tpl_group["texts"])
    
    # 장소 이름이나 특색 살짝 반영 (일부 리뷰에 장소명 자연스럽게 가미)
    p_name = place['name']
    if random.random() < 0.35:
        prefix_phrases = [
            f"'{p_name}' 처음 와봤는데, ",
            f"역시 소문대로 {p_name} 분위기 좋네요! ",
            f"{p_name} 둘러보고 왔어요. ",
            f"오랜만에 {p_name} 들렀는데 ",
            f"이번 여행 코스로 {p_name} 넣길 정말 잘했어요. "
        ]
        text_content = random.choice(prefix_phrases) + text_content
    
    # 생성 시각 분산: 0~14일 사이 (랜덤 분/초)
    # 오늘 생성된 리뷰도 30~40개 포함되도록 (오늘 이야기 필터 지원)
    if i < 40:
        minutes_ago = random.randint(15, 600) # 15분 전 ~ 10시간 전
        created_at = now - datetime.timedelta(minutes=minutes_ago)
    else:
        hours_ago = random.randint(12, 14 * 24)
        created_at = now - datetime.timedelta(hours=hours_ago, minutes=random.randint(0, 59))
        
    mood = tpl_group["mood"]
    # 톡 튀는 별점 (대부분 5점, 일부 솔직한 4점, 가끔 3점)
    score_rnd = random.random()
    if score_rnd < 0.75:
        score = 5
    elif score_rnd < 0.95:
        score = 4
    else:
        score = 3
        
    tags = tpl_group["tags"].copy()
    if random.random() < 0.5:
        tags.append("온마루")
    
    reviews.append({
        'id': rev_id,
        'member_id': user['id'],
        'place_id': place['placeId'], # public_place_id
        'name': place['name'],
        'address': place.get('address', ''),
        'lat': place.get('coordinates', {}).get('lat', place.get('lat', 37.5)),
        'lng': place.get('coordinates', {}).get('lng', place.get('lng', 127.0)),
        'text': text_content,
        'mood': mood,
        'score': score,
        'tags': tags,
        'created_at': created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

print(f"Generated {len(reviews)} reviews")

# SQL 생성
# 1. onmaru.identity_members
# 2. onmaru.identity_member_profiles
# 3. onmaru.community_visit_reviews (place_id는 catalog_place_public_ids 와 조인해서 실제 place_id uuid 매핑)
sql_lines = [
    "-- ====================================================================",
    "-- OnMaru Warmth Reviews 300 Seeding Script (Issue #643 / Demonstration)",
    "-- Real-life traveler tone, geographically distributed across landmarks.",
    "-- ====================================================================",
    "\\set ON_ERROR_STOP on",
    "BEGIN;",
    "",
    "-- 1. Virtual Members Creation",
]

for u in users:
    sql_lines.append(f"INSERT INTO onmaru.identity_members (id, status, created_at) VALUES ('{u['id']}', 'ACTIVE', '{u['created_at']}'::timestamptz) ON CONFLICT (id) DO NOTHING;")

sql_lines.append("")
sql_lines.append("-- 2. Virtual Member Profiles Creation")
for u in users:
    sql_lines.append(f"INSERT INTO onmaru.identity_member_profiles (member_id, display_name, character_id, background_id, created_at, updated_at) "
                     f"VALUES ('{u['id']}', '{u['nickname']}', '{u['character_id']}', '{u['background_id']}', '{u['created_at']}'::timestamptz, '{u['created_at']}'::timestamptz) "
                     f"ON CONFLICT (member_id) DO UPDATE SET display_name = EXCLUDED.display_name, character_id = EXCLUDED.character_id, background_id = EXCLUDED.background_id;")

sql_lines.append("")
sql_lines.append("-- 3. Seed 300 Community Visit Reviews (Matched to active public place IDs)")
sql_lines.append("CREATE TEMP TABLE temp_seed_reviews (")
sql_lines.append("    id uuid PRIMARY KEY,")
sql_lines.append("    member_id uuid NOT NULL,")
sql_lines.append("    public_place_id varchar NOT NULL,")
sql_lines.append("    place_name varchar NOT NULL,")
sql_lines.append("    text text NOT NULL,")
sql_lines.append("    mood varchar(16),")
sql_lines.append("    score smallint,")
sql_lines.append("    tags jsonb,")
sql_lines.append("    latitude numeric(9,6),")
sql_lines.append("    longitude numeric(9,6),")
sql_lines.append("    created_at timestamptz NOT NULL")
sql_lines.append(") ON COMMIT DROP;")
sql_lines.append("")
sql_lines.append("INSERT INTO temp_seed_reviews VALUES")

val_rows = []
for r in reviews:
    t_clean = r['text'].replace("'", "''")
    name_clean = r['name'].replace("'", "''")
    tags_json = json.dumps(r['tags'], ensure_ascii=False)
    val_rows.append(f"('{r['id']}', '{r['member_id']}', '{r['place_id']}', '{name_clean}', '{t_clean}', '{r['mood']}', {r['score']}, '{tags_json}'::jsonb, {r['lat']:.6f}, {r['lng']:.6f}, '{r['created_at']}'::timestamptz)")

sql_lines.append(",\n".join(val_rows) + ";")

sql_lines.append("""
-- Actual Insert with place_id resolved from catalog_place_public_ids
INSERT INTO onmaru.community_visit_reviews (
    id, member_id, place_id, text, status, created_at, mood, score, tags,
    public_place_id, place_name, region_code, latitude, longitude
)
SELECT 
    t.id,
    t.member_id,
    p.place_id,
    t.text,
    'PUBLISHED' AS status,
    t.created_at,
    t.mood,
    t.score,
    t.tags,
    t.public_place_id,
    t.place_name,
    NULL AS region_code,
    t.latitude,
    t.longitude
FROM temp_seed_reviews t
JOIN onmaru.catalog_place_public_ids p ON p.public_id = t.public_place_id
ON CONFLICT (id) DO UPDATE SET
    text = EXCLUDED.text,
    mood = EXCLUDED.mood,
    score = EXCLUDED.score,
    tags = EXCLUDED.tags,
    created_at = EXCLUDED.created_at;

SELECT count(*) AS inserted_or_updated_reviews FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500700-%';
COMMIT;
""")

with open("scripts/seed-reviews/seed-warmth-reviews-300.sql", "w", encoding="utf-8") as f:
    f.write("\n".join(sql_lines))

print("Successfully written scripts/seed-reviews/seed-warmth-reviews-300.sql")
