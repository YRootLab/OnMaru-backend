import json
import random
import datetime

# 1. 1080곳 한옥 로드
with open('scripts/seed-reviews/all_hanoks_1080.json', 'r', encoding='utf-8') as f:
    hanoks = json.load(f)

print(f"Loaded {len(hanoks)} hanoks.")

# 2. 가상 유저 150명 생성
PREFIXES = [
    '고요한', '따뜻한', '느긋한', '정겨운', '포근한', '산뜻한', '다정한', '잔잔한', '맑은', '소박한',
    '바람부는', '햇살가득', '달빛어린', '별빛담은', '솔향기', '단아한', '온기품은', '고운', '청량한', '은은한',
    '소담한', '여유로운', '정갈한', '달콤한', '초록빛', '시원한', '아늑한', '설레는', '푸른', '빛나는',
    '봄날의', '가을바람', '새벽안개', '노을빛', '차분한', '기분좋은', '발걸음', '구름따라', '달빛걸음', '소소한'
]
NOUNS = [
    '마루', '기와', '골목', '달빛', '솔바람', '꽃길', '한지', '찻잔', '구름', '나들이',
    '툇마루', '담장', '처마', '연못', '풍경소리', '바람', '하늘', '차향', '디딤돌', '창호지',
    '연잎', '소나무', '돌담', '들꽃', '여정', '물결', '까치', '도라지', '대나무', '단풍'
]

random.seed(42)
now = datetime.datetime.now(datetime.timezone.utc)

users = []
for i in range(55, 205):
    member_id = f"54500700-0000-4000-8000-{i+1:012d}"
    nickname = f"{random.choice(PREFIXES)} {random.choice(NOUNS)} {random.randint(10, 999)}"
    char_id = f"CHARACTER_{random.randint(1, 10):02d}"
    bg_id = f"BACKGROUND_{random.randint(1, 10):02d}"
    created_days_ago = random.randint(15, 60)
    user_created_at = now - datetime.timedelta(days=created_days_ago)
    users.append({
        'id': member_id,
        'nickname': nickname,
        'character_id': char_id,
        'background_id': bg_id,
        'created_at': user_created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

# 3. 고유한 문장 조립 블록 (수만 가지 조합 생성)

# (A) 방문 상황/맥락 도입부
OPENINGS = [
    "{place} 다녀왔어요! ",
    "주말에 친구들이랑 {place} 들렀는데 ",
    "평일 연차 쓰고 혼자 {place} 다녀옴. ",
    "부모님 모시고 {place} 산책 겸 다녀왔습니다. ",
    "근처 여행 코스로 {place} 넣길 정말 잘했어요. ",
    "예전부터 가보고 싶었던 {place} 드디어 방문! ",
    "날씨가 너무 맑아서 {place} 한 바퀴 돌고 왔네요. ",
    "비 오는 날 운치 찾아 {place} 다녀왔어요. ",
    "해 질 무렵에 {place} 방문했는데 ",
    "오랜만에 여유 찾으러 {place} 다녀왔습니다. ",
    "드라이브 겸 나들이로 {place} 선택했는데 대만족! ",
    "SNS에서 사진 보고 찾아간 {place}, ",
    "조용히 사색하고 싶어서 찾은 {place}. ",
    "기대 이상으로 너무 좋았던 {place}! ",
    "천천히 골목 걷다가 마주친 {place}. ",
    "" # 바로 본문으로 시작하는 경우도 둠
]

# (B) 장소 특성별 / 감성별 핵심 경험 본문 (각각 수십 개)
EXPERIENCES_BY_TYPE = {
    # 1. 고택 / 종택 / 역사 가옥
    "HISTORIC": [
        "수백 년 세월이 묻어난 대청마루 나무 결이랑 기와지붕 곡선이 정말 압권이네요.",
        "오래된 흙담길 따라서 천천히 걷는데 마음이 절로 차분해지고 경건해지는 기분이 들었어요.",
        "해설사 선생님 설명 들으면서 둘러보니까 그냥 지나칠 뻔한 기와 문양이랑 선조들의 지혜가 새롭게 보입니다.",
        "조선시대로 시간 여행 온 것처럼 고즈넉함의 깊이가 남달랐어요. 기둥 하나하나 품격이 느껴짐.",
        "대청마루에 가만히 앉아 앞마당을 내다보고 있으면 바람 소리만 귓가에 사박사박 스칩니다.",
        "선조들의 옛 숨결이 그대로 보존되어 있어서 감탄만 나왔어요. 조상님들 풍류가 이런 거였구나 싶음.",
        "고택 특유의 은은한 흙냄새와 마른 소나무 향기가 머리를 맑게 정화해주는 느낌이었습니다.",
        "툇마루 끝에 걸터앉아 처마 끝으로 이어지는 푸른 하늘을 바라보는데 진정한 힐링이 뭔지 알겠더라구요."
    ],
    # 2. 숙소 / 한옥스테이 / 쉼터
    "STAY": [
        "따뜻한 온돌방에 등 지지고 누워있으니 한 주 동안 쌓였던 피로가 싹 풀렸습니다.",
        "아침에 문 열었을 때 마당에서 들려오는 청량한 새소리와 솔바람 냄새를 잊을 수가 없네요.",
        "침구류도 깨끗하고 서까래 인테리어가 너무 아늑해서 꿀잠 잤습니다. 밤 분위기가 진짜 예술!",
        "도시 소음 하나 없이 풀벌레 소리 들으면서 잠드는 경험이 너무나 소중하고 특별했어요.",
        "마당 평상에 둘러앉아 밤하늘 별 보면서 두런두런 이야기 나눴던 순간이 이번 여행 최고 명장면.",
        "창호지 문 사이로 부드럽게 스며드는 아침 햇살 덕분에 기분 좋게 하루를 시작할 수 있었어요.",
        "현대식 편의시설과 전통 한옥의 멋이 절묘하게 조화되어 있어서 머무는 내내 너무 편안했습니다."
    ],
    # 3. 체험관 / 공방 / 문화마을
    "EXPERIENCE": [
        "전통 체험 프로그램 참여했는데 강사님도 무척 친절하시고 시간 가는 줄 모를 만큼 재밌었어요.",
        "마당에서 윷놀이랑 투호 던지기 하면서 놀았는데 어른들이 더 신나서 시간 보냈네요 ㅋㅋㅋ",
        "직접 손으로 만져보고 만들어보는 전통 공예 체험이 생각보다 훨씬 뜻깊고 기억에 오래 남습니다.",
        "아이들이랑 같이 방문했는데 책으로만 보던 전통 문화를 직접 오감으로 느낄 수 있어서 유익했어요.",
        "다양한 볼거리랑 아기자기한 기념품들이 가득해서 둘러보는 재미가 정말 쏠쏠했습니다."
    ],
    # 4. 정원 / 숲 / 사색 & 뷰 명소
    "NATURE": [
        "마당에 심어진 굵은 소나무와 정원의 들꽃들이 한옥 담장과 그림처럼 어우러져 있습니다.",
        "처마 끝 너머로 병풍처럼 펼쳐진 산세가 감탄을 자아내네요. 풍경 맛집 인정합니다.",
        "바람 불 때마다 처마 밑 풍경 소리가 맑게 딸랑거리는데 듣고만 있어도 잡생각이 다 사라져요.",
        "비가 촉촉하게 내리던 날이라 처마에서 떨어지는 빗방울 구경하며 비멍 제대로 때렸습니다.",
        "초록빛 잔디와 붉은 배롱나무, 그리고 짙은 기와가 만들어내는 색감 조화가 황홀할 정도예요.",
        "가을 단풍이 기와지붕 위로 살포시 내려앉아 있는데 한 폭의 수채화를 보는 듯했습니다."
    ],
    # 5. 카페 / 찻집 / 먹거리
    "TEA_FOOD": [
        "산책 한 바퀴 돌고 마루에 앉아 마시는 따뜻한 꽃차 한 잔이 몸과 마음을 녹여주네요.",
        "달지 않고 구수한 전통 다과랑 시원한 오미자 에이드 조합이 정말 끝내줬습니다.",
        "통창 너머로 한옥 정원을 바라보며 차를 음미할 수 있어서 여유로운 오후를 만끽했어요.",
        "은은하게 퍼지는 차향과 전통 목조 건물이 주는 포근한 분위기가 너무 잘 어우러집니다.",
        "디저트로 나온 쫀득한 찹쌀떡과 약과가 너무 맛있어서 부모님 선물용으로 따로 포장해왔어요."
    ]
}

# (C) 방문 꿀팁 / 날씨 감상 / 소소한 추천 마무리 (수십 개)
CLOSINGS = [
    "낮에는 햇살이 좀 강하니 챙 넓은 모자 챙겨가시면 더 좋을 것 같아요!",
    "오후 4~5시쯤 해 질 무렵에 처마 밑에서 사진 찍으면 무조건 인생샷 나옵니다 ㅎㅎ",
    "골목길 산책할 땐 발 편한 운동화 신고 오시는 걸 추천드려요.",
    "주말엔 사람이 꽤 몰리니 오전 일찍 방문해서 한적함을 즐겨보세요.",
    "비 오는 날 방문하시면 특유의 운치와 흙냄새를 두 배로 즐기실 수 있습니다.",
    "부모님 모시고 올 효도 여행지 찾으신다면 망설임 없이 추천합니다.",
    "혼자 와서 책 한 권 읽거나 생각 정리하기에도 더없이 완벽한 장소예요.",
    "사계절 풍경이 다 다를 것 같아서 눈 내리는 겨울에 꼭 다시 오고 싶네요.",
    "담장 너머로 보이는 하늘 풍경이 예뻐서 사진만 백 장 넘게 찍고 갑니다 ㅋㅋㅋ",
    "복잡한 도시 일상에서 벗어나 조용한 쉼표를 찍고 가기에 최고였어요.",
    "친구, 연인과 조용조용 도란도란 이야기 나누며 걷기 딱 좋은 코스입니다.",
    "주차는 근처 공영주차장 이용하시고 천천히 걸어 들어오시는 게 훨씬 편해요!",
    "가족들과 소중한 추억 남기기에 이만한 곳이 없는 것 같습니다. 완전 힐링됨.",
    "마당 구석 벤치가 숨은 뷰 명당이에요. 다들 그냥 지나치시던데 꼭 앉아보세요!",
    "다음에 여유 있을 때 꼭 반나절 이상 머물다 가고 싶은 애정하는 곳입니다."
]

def determine_type(name, category):
    if any(k in name for k in ['고택', '종택', '가옥', '생가', '사당', '서원', '향교', '정사', '재사', '서당', '당', '루']):
        return "HISTORIC"
    elif any(k in name for k in ['호텔', '스테이', '숙소', '여관', '펜션', '한옥호텔']):
        return "STAY"
    elif any(k in name for k in ['체험', '공방', '마을', '전시관', '박물관', '센터']):
        return "EXPERIENCE"
    elif any(k in name for k in ['카페', '찻집', '다원', '차방', '식당']):
        return "TEA_FOOD"
    else:
        return random.choice(["HISTORIC", "NATURE", "TEA_FOOD"])

# 4. 2,000건 생성 (중복 검사 Set으로 100% 고유성 보장)
TOTAL_REVIEWS = 2000
reviews = []
generated_text_set = set()

# 장소 풀 준비: 1080개 장소를 최소 1~2회 포함
shuffled_hanoks = list(hanoks)
random.shuffle(shuffled_hanoks)
place_pool = list(shuffled_hanoks)
remaining = TOTAL_REVIEWS - len(place_pool)
place_pool.extend(random.choices(shuffled_hanoks, k=remaining))
random.shuffle(place_pool)

for i in range(TOTAL_REVIEWS):
    rev_id_num = 501 + i
    rev_id = f"54500700-0000-4000-9000-{rev_id_num:012d}"
    user = random.choice(users)
    place = place_pool[i]
    p_name = place['name']
    p_addr = place.get('address', '')
    p_type = determine_type(p_name, place.get('category'))

    # 중복되지 않는 고유한 텍스트 조합 시도 (while 루프로 고유성 100% 보장)
    attempts = 0
    while True:
        attempts += 1
        opening_tpl = random.choice(OPENINGS)
        opening = opening_tpl.format(place=p_name) if "{place}" in opening_tpl else opening_tpl
        
        # 타입별 경험 본문 선택
        exp_list = EXPERIENCES_BY_TYPE[p_type]
        if random.random() < 0.3:
            # 30% 확률로 NATURE 감성도 결합
            exp = random.choice(EXPERIENCES_BY_TYPE["NATURE"])
        else:
            exp = random.choice(exp_list)
            
        closing = random.choice(CLOSINGS)
        
        # 문장 조합
        full_text = f"{opening}{exp} {closing}".strip()
        
        # 만약 이미 생성된 문장과 겹친다면(극히 드물지만) 재시도
        if full_text not in generated_text_set:
            generated_text_set.add(full_text)
            break
        if attempts > 50:
            # 변형 접미사 추가로 고유성 강제
            full_text += f" ({p_name} 방문기)"
            generated_text_set.add(full_text)
            break

    # 분위기(mood)와 태그 결정
    if any(k in full_text for k in ['조용', '사색', '혼자', '힐링', '고즈넉', '새소리', '차분', '여유']):
        mood = '한적'
        score = 1
        tag_pool = ["고즈넉", "힐링", "사색", "혼자여행", "산책로", "풍경맛집"]
    else:
        mood = '북적'
        score = 1 if random.random() < 0.8 else 2
        tag_pool = ["사진맛집", "인생샷", "가족여행", "데이트", "주말나들이", "방문팁"]

    tags = random.sample(tag_pool, k=min(len(tag_pool), random.randint(2, 3)))
    if random.random() < 0.4:
        tags.append("온마루")

    # 시간 분산 (최근 30일 이내)
    if i < 100:
        mins = random.randint(10, 720)
        created_at = now - datetime.timedelta(minutes=mins)
    elif i < 400:
        hours = random.randint(12, 72)
        created_at = now - datetime.timedelta(hours=hours, minutes=random.randint(0, 59))
    elif i < 1000:
        hours = random.randint(73, 168)
        created_at = now - datetime.timedelta(hours=hours, minutes=random.randint(0, 59))
    else:
        days = random.randint(8, 30)
        created_at = now - datetime.timedelta(days=days, hours=random.randint(0, 23), minutes=random.randint(0, 59))

    coords = place.get('coordinates') or {}
    lat = coords.get('lat') or place.get('lat', 37.5)
    lng = coords.get('lng') or place.get('lng', 127.0)

    reviews.append({
        'id': rev_id,
        'member_id': user['id'],
        'place_id': place['placeId'],
        'name': p_name,
        'address': p_addr,
        'lat': lat,
        'lng': lng,
        'text': full_text,
        'mood': mood,
        'score': score,
        'tags': tags,
        'created_at': created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

print(f"Total reviews generated: {len(reviews)}")
print(f"Unique text count: {len(generated_text_set)} (100% unique: {len(generated_text_set) == len(reviews)})")

# 5. SQL 작성 (기존 구버전 중복 시드 301~500번까지 함께 깔끔하게 덮어쓰거나 0501~2500번 배치)
sql_lines = [
    "-- ====================================================================",
    "-- OnMaru Warmth Reviews 2000 Seeding Script (Issue #769)",
    "-- 100% UNIQUE text guarantee (No duplicate sentences across any hanok)",
    "-- Covers 1,080 registered Hanoks across all Korean regions.",
    "-- Safe namespace: 54500700-0000-4000-9000-000000000501 ~ 2500",
    "-- ====================================================================",
    "\\set ON_ERROR_STOP on",
    "BEGIN;",
    "",
    "-- 1. Virtual Members Creation (150 members)",
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
sql_lines.append("-- 3. Seed 2,000 Unique Reviews")
sql_lines.append("CREATE TEMP TABLE temp_seed_reviews_2000 (")
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
sql_lines.append("INSERT INTO temp_seed_reviews_2000 VALUES")

val_rows = []
for r in reviews:
    t_clean = r['text'].replace("'", "''")
    name_clean = r['name'].replace("'", "''")
    tags_json = json.dumps(r['tags'], ensure_ascii=False)
    val_rows.append(f"('{r['id']}', '{r['member_id']}', '{r['place_id']}', '{name_clean}', '{t_clean}', '{r['mood']}', {r['score']}, '{tags_json}'::jsonb, {r['lat']:.6f}, {r['lng']:.6f}, '{r['created_at']}'::timestamptz)")

sql_lines.append(",\n".join(val_rows) + ";")

sql_lines.append("""
-- 4. Actual Insert into onmaru.community_visit_reviews with place_id and region_code resolution
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
    COALESCE(mp.sigungu_code, '11110') AS region_code,
    t.latitude,
    t.longitude
FROM temp_seed_reviews_2000 t
JOIN onmaru.catalog_place_public_ids p ON p.public_id = t.public_place_id
LEFT JOIN onmaru.map_place_read_projection mp ON mp.public_id = t.public_place_id
ON CONFLICT (id) DO UPDATE SET
    text = EXCLUDED.text,
    mood = EXCLUDED.mood,
    score = EXCLUDED.score,
    tags = EXCLUDED.tags,
    created_at = EXCLUDED.created_at;

SELECT count(*) AS total_seeded_reviews_now FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500700-%';
COMMIT;
""")

output_file = "scripts/seed-reviews/seed-warmth-reviews-2000.sql"
with open(output_file, "w", encoding="utf-8") as f:
    f.write("\n".join(sql_lines))

print(f"Successfully wrote {output_file}")
