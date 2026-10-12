import json
import random
import datetime

with open('scripts/seed-reviews/regional_places.json', 'r', encoding='utf-8') as f:
    regional_data = json.load(f)

# 권역별 대표 장소 선별 (총 60~70개 장소 추출)
all_regional_places = []
for reg, items in regional_data.items():
    # 각 지역별로 8~10개 대표 장소
    selected = items[:10]
    for p in selected:
        p['region_tag'] = reg
        all_regional_places.append(p)

print(f"Total target regional places for additional 200 reviews: {len(all_regional_places)}")

# 가상 유저 풀 (추가 20명 생성: 54500700-0000-4000-8000-000000000036 ~ 0055)
PREFIXES = ['고운', '청량한', '은은한', '소담한', '여유로운', '정갈한', '달콤한', '초록빛', '시원한', '포근한', '단아한', '아늑한', '설레는', '푸른', '빛나는']
NOUNS = ['바람', '기와', '골목', '하늘', '차향', '디딤돌', '창호지', '마루', '풍경', '연잎', '소나무', '돌담', '들꽃', '여정', '물결']

random.seed(101) # 재현 가능 시드

new_users = []
for i in range(35, 55):
    member_id = f"54500700-0000-4000-8000-{i+1:012d}"
    nickname = f"{random.choice(PREFIXES)} {random.choice(NOUNS)} {random.randint(10, 999)}"
    char_id = f"CHARACTER_{random.randint(1, 10):02d}"
    bg_id = f"BACKGROUND_{random.randint(1, 10):02d}"
    created_days_ago = random.randint(10, 30)
    user_created_at = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(days=created_days_ago)
    new_users.append({
        'id': member_id,
        'nickname': nickname,
        'character_id': char_id,
        'background_id': bg_id,
        'created_at': user_created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

# 지역별 특화 리얼 리뷰 템플릿
TEMPLATES_BY_REGION = {
    '경주': [
        ("황리단길 구경하다가 안쪽 한옥 골목으로 살짝 들어오니까 분위기 완전 고즈넉하고 좋아요. 사진 오백 장 찍음!", "북적", ["사진맛집", "황리단길", "데이트"]),
        ("경주 특유의 낮고 넓은 한옥 능선 뷰가 너무 멋집니다. 해질 때 노을이랑 기와 능선 꼭 보세요.", "한적", ["풍경맛집", "고즈넉", "힐링"]),
        ("돌담길 따라서 천천히 걷는데 바람도 시원하고 마음이 절로 차분해지네요. 경주 여행 필수 코스!", "한적", ["산책로", "고즈넉", "힐링"]),
        ("주말이라 사람은 꽤 많았는데 마당 정원이 워낙 예뻐서 기다릴 만했어요. 시원한 식혜 한 잔 최고!", "북적", ["방문팁", "데이트", "한옥카페"])
    ],
    '안동': [
        ("안동 고택의 묵직한 멋은 진짜 다른 데랑 비교가 안 되네요. 대청마루에 앉아있으면 조선시대로 타임슬립한 기분.", "한적", ["고택", "역사체험", "부모님과"]),
        ("하회마을 쪽 둘러보고 들렀는데 전통 한옥의 원형이 그대로 보존되어 있어서 너무 감동적이었어요.", "한적", ["하회마을", "가족여행", "힐링"]),
        ("마을 전체가 고요해서 툇마루에 가만히 앉아 새소리 듣는 것만으로도 제대로 힐링됩니다.", "한적", ["고즈넉", "사색", "혼자여행"]),
        ("나무 결 하나하나 선조들의 지혜가 느껴져요. 부모님 모시고 효도 여행 코스로 완전 추천합니다.", "한적", ["부모님과", "효도여행", "역사체험"])
    ],
    '강릉': [
        ("동해 바다 보러 왔다가 들른 한옥인데 솔향기 솔솔 나고 운치 최고네요. 소나무 정원이 예술입니다.", "한적", ["솔향기", "바다근처", "풍경맛집"]),
        ("강릉 오면 맨날 바다만 봤는데 이런 고즈넉한 한옥 명소가 숨어있었다니... 안 왔으면 후회할 뻔!", "한적", ["숨은명소", "고즈넉", "데이트"]),
        ("기와 처마 너머로 파란 하늘이랑 소나무 숲이 어우러져서 사진이 진짜 잘 나와요. 인생샷 건짐!", "북적", ["사진맛집", "인생샷", "강릉여행"]),
        ("차 한 잔 마시면서 바람 쐬기 너무 좋은 곳. 커피거리 들렀다가 여기 오면 딱 좋은 힐링 코스예요.", "한적", ["티타임", "힐링", "데이트"])
    ],
    '수원_경기': [
        ("행궁동 성곽길 산책하다가 들렀는데 고즈넉하면서도 세련된 감성이 너무 매력적이에요.", "북적", ["행궁동", "성곽길", "데이트"]),
        ("주말 낮엔 사람 북적거리는데 그 활기찬 분위기가 좋아요. 주변 골목마다 예쁜 소품샵도 많음!", "북적", ["주말나들이", "핫플", "데이트"]),
        ("수원 화성 야경 보고 들렀는데 밤에 조명 켜진 한옥이 진짜 낭만적입니다. 밤 산책 강추!", "한적", ["야경명소", "밤산책", "분위기맛집"])
    ],
    '충청': [
        ("공주 부여 백제 문화권 한옥은 단아하고 소박한 멋이 있어서 더 정이 가요. 마음이 편안해집니다.", "한적", ["백제역사", "단아함", "가족여행"]),
        ("마을 돌담길이랑 흙길이 너무 정겨워요. 어릴 적 시골 할머니 댁 온 것처럼 포근한 느낌!", "한적", ["정겨운", "포근함", "힐링"]),
        ("사람 바글거리지 않고 여유롭게 둘러볼 수 있어서 너무 좋았어요. 사색하기 딱 좋은 곳.", "한적", ["여유", "고즈넉", "혼자여행"])
    ],
    '영남_대구': [
        ("도심 한가운데 이런 멋스러운 한옥이 숨어있다니 놀라워요. 옛것과 현대가 공존하는 묘한 매력!", "북적", ["도심속한옥", "이색명소", "데이트"]),
        ("대청마루에서 시원한 오미자차 한 잔 마시면서 쉬어가기 딱 좋습니다. 사진도 엄청 잘 나옴.", "한적", ["전통차", "힐링", "사진맛집"]),
        ("가족들이랑 주말 나들이로 다녀왔는데 마당도 넓고 아이들도 신나게 뛰어놀았네요 ㅎㅎ", "북적", ["가족나들이", "주말여행", "아이와함께"])
    ],
    '호남_남원': [
        ("남원 춘향골 정취가 물씬 풍기는 멋진 한옥입니다. 정자랑 연못 뷰가 한 폭의 그림 같아요.", "한적", ["남원여행", "연못뷰", "풍경맛집"]),
        ("대숲 바람 소리 사각사각 들리고 처마 밑에 앉아있으니 신선놀음이 따로 없네요. 강력 추천!", "한적", ["대숲바람", "고즈넉", "힐링"]),
        ("전통 체험 프로그램도 잘 되어 있어서 재밌게 즐기고 갑니다. 기념품도 하나 건졌어요!", "북적", ["전통체험", "데이트", "주말나들이"])
    ],
    '제주': [
        ("제주 돌담이랑 전통 초가/한옥 감성이 섞여서 이국적이면서도 독특해요! 귤나무랑 조화 대박.", "한적", ["제주돌담", "이색한옥", "사진맛집"]),
        ("바람 많은 제주에서 만난 아늑한 한옥 쉼터. 조용히 파도 소리랑 바람 소리 들으며 쉬어감.", "한적", ["제주여행", "힐링", "고즈넉"]),
        ("제주도에서 한옥이라니 색다르고 너무 좋았어요. 마당에 핀 들꽃들도 너무 예쁨!", "북적", ["제주핫플", "데이트", "인생샷"])
    ]
}

now = datetime.datetime.now(datetime.timezone.utc)
additional_reviews = []

# 200개 생성 (ID: 54500700-0000-4000-9000-000000000301 ~ 000000000500)
for i in range(300, 500):
    rev_id = f"54500700-0000-4000-9000-{i+1:012d}"
    user = random.choice(new_users)
    place = all_regional_places[i % len(all_regional_places)]
    reg_tag = place.get('region_tag', '경주')
    
    # 템플릿 선택
    tpl_choices = TEMPLATES_BY_REGION.get(reg_tag, TEMPLATES_BY_REGION['경주'])
    text_base, mood, tags_base = random.choice(tpl_choices)
    
    p_name = place['name']
    if random.random() < 0.4:
        text = f"{p_name} 다녀왔어요! " + text_base
    else:
        text = text_base
        
    # score: 1(최고/좋아요) 75%, 2(만족) 20%, 3(보통) 5%
    rnd = random.random()
    if rnd < 0.75:
        score = 1
    elif rnd < 0.95:
        score = 2
    else:
        score = 3
        
    # 시간 분산 (최근 12일 분산, 오늘 날짜도 포함)
    if i < 330:
        minutes_ago = random.randint(20, 500)
        created_at = now - datetime.timedelta(minutes=minutes_ago)
    else:
        hours_ago = random.randint(10, 12 * 24)
        created_at = now - datetime.timedelta(hours=hours_ago, minutes=random.randint(0, 59))
        
    tags = tags_base.copy()
    if random.random() < 0.4:
        tags.append("온마루")
        
    lat = place.get('coordinates', {}).get('lat', place.get('lat', 35.8))
    lng = place.get('coordinates', {}).get('lng', place.get('lng', 128.5))
    
    additional_reviews.append({
        'id': rev_id,
        'member_id': user['id'],
        'place_id': place['placeId'],
        'name': p_name,
        'lat': lat,
        'lng': lng,
        'text': text,
        'mood': mood,
        'score': score,
        'tags': tags,
        'created_at': created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

print(f"Generated {len(additional_reviews)} additional reviews")

# SQL 생성
sql_lines = [
    "\\set ON_ERROR_STOP on",
    "BEGIN;",
    "",
    "-- 1. Virtual Members Creation (36~55)",
]

for u in new_users:
    sql_lines.append(f"INSERT INTO onmaru.identity_members (id, status, created_at) VALUES ('{u['id']}', 'ACTIVE', '{u['created_at']}'::timestamptz) ON CONFLICT (id) DO NOTHING;")

sql_lines.append("")
sql_lines.append("-- 2. Virtual Member Profiles Creation")
for u in new_users:
    sql_lines.append(f"INSERT INTO onmaru.identity_member_profiles (member_id, display_name, character_id, background_id, created_at, updated_at) "
                     f"VALUES ('{u['id']}', '{u['nickname']}', '{u['character_id']}', '{u['background_id']}', '{u['created_at']}'::timestamptz, '{u['created_at']}'::timestamptz) "
                     f"ON CONFLICT (member_id) DO UPDATE SET display_name = EXCLUDED.display_name, character_id = EXCLUDED.character_id, background_id = EXCLUDED.background_id;")

sql_lines.append("")
sql_lines.append("CREATE TEMP TABLE temp_additional_reviews (")
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
sql_lines.append("INSERT INTO temp_additional_reviews VALUES")

val_rows = []
for r in additional_reviews:
    t_clean = r['text'].replace("'", "''")
    name_clean = r['name'].replace("'", "''")
    tags_json = json.dumps(r['tags'], ensure_ascii=False)
    val_rows.append(f"('{r['id']}', '{r['member_id']}', '{r['place_id']}', '{name_clean}', '{t_clean}', '{r['mood']}', {r['score']}, '{tags_json}'::jsonb, {r['lat']:.6f}, {r['lng']:.6f}, '{r['created_at']}'::timestamptz)")

sql_lines.append(",\n".join(val_rows) + ";")

sql_lines.append("""
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
FROM temp_additional_reviews t
JOIN onmaru.catalog_place_public_ids p ON p.public_id = t.public_place_id
LEFT JOIN onmaru.map_place_read_projection mp ON mp.public_id = t.public_place_id
ON CONFLICT (id) DO UPDATE SET
    text = EXCLUDED.text,
    mood = EXCLUDED.mood,
    score = EXCLUDED.score,
    tags = EXCLUDED.tags,
    created_at = EXCLUDED.created_at;

SELECT count(*) AS total_reviews_now FROM onmaru.community_visit_reviews WHERE id::text LIKE '54500700-%';
COMMIT;
""")

with open('scripts/seed-reviews/seed-additional-200.sql', 'w', encoding='utf-8') as f:
    f.write('\n'.join(sql_lines))

print("Successfully generated scripts/seed-reviews/seed-additional-200.sql")
