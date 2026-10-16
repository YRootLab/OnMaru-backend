import json
import random
import datetime

# 1. 1080곳 한옥 로드
with open('scripts/seed-reviews/all_hanoks_1080.json', 'r', encoding='utf-8') as f:
    hanoks = json.load(f)

print(f"Loaded {len(hanoks)} hanoks.")

# 2. 가상 유저 150명 생성 (기존 55명에 이어 56 ~ 205)
# 54500700-0000-4000-8000-000000000056 ~ 54500700-0000-4000-8000-000000000205
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

random.seed(20261016)

users = []
now = datetime.datetime.now(datetime.timezone.utc)

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

print(f"Generated {len(users)} virtual users.")

# 3. 자연스럽고 생생한 리얼 경험 템플릿 풀 (지역/상황/감성/방문팁)
# AI 티가 안 나고, 디테일한 상황 묘사, 구어체 및 이모지/말투 포함

COMMON_TEMPLATES = [
    # 고즈넉 & 힐링
    {
        "mood": "한적", "score": 1,
        "tags": ["고즈넉", "힐링", "혼자여행", "조용한"],
        "texts": [
            "일부러 연차 쓰고 평일 오전에 왔더니 진짜 조용하고 평화롭네요. 마루에 가만히 앉아만 있어도 힐링됨.",
            "바람 불 때마다 풍경 소리 딸랑거리는 게 너무 좋았어요. 머릿속 복잡할 때 혼자 산책 오기 딱입니다.",
            "주말엔 사람 많다던데 평일 늦은 오후는 완전 다른 세상이네요. 고즈넉한 한옥 정취 제대로 느끼고 갑니다.",
            "혼자 조용히 사색하기에 이만한 곳이 없어요. 마루 끝에 걸터앉아서 새소리 듣는 시간이 제일 행복했음.",
            "아침 일찍 개방 시간 맞춰서 갔더니 발소리만 사박사박 들려요. 복잡한 도시 벗어나서 힐링 제대로 하고 옴.",
            "툇마루에 걸터앉아서 멍때리는데 풀벌레 소리 들리고... 이게 진짜 쉬는 거지 싶었음.",
            "아무 생각 없이 한 시간 동안 가만히 앉아있었는데 잡생각 싹 사라짐. 조용해서 너무 마음에 들어요."
        ]
    },
    # 사진 맛집 & 인생샷
    {
        "mood": "북적", "score": 1,
        "tags": ["사진맛집", "인생샷", "데이트", "주말나들이"],
        "texts": [
            "주말 낮에 오니까 사람 진짜 많은데 골목 사이로 한복 입고 사진 찍는 분들 많아서 분위기 활기차고 좋아요!",
            "주말이라 북적거리긴 한데 처마 밑 햇살 들어올 때 사진 찍으면 인생샷 무조건 건집니다 ㅎㅎ",
            "생각보다 사람 많아서 놀랐는데 구석구석 포토존이 진짜 많네요. 오후 4시쯤 노을 질 때 강추!",
            "골목마다 아기자기한 볼거리가 넘침. 사람 많아도 이 맛에 오는 거 아니겠습니까? 주말엔 활기차서 더 좋아요.",
            "친구들이랑 한복 대여해서 갔는데 골목길 채광이 사기예요 ㅋㅋㅋ 사람 많아도 사진은 다 건졌음!",
            "기와 처마선이랑 푸른 하늘 걸어서 찍으면 막 찍어도 엽서처럼 나와요. 인스타 사진 건짐!",
            "역광 피해서 마당 쪽 감나무 밑에서 찍어보세요. 분위기 진짜 예쁘게 잘 나옵니다."
        ]
    },
    # 비 오는 날 운치
    {
        "mood": "한적", "score": 1,
        "tags": ["비오는날", "고즈넉", "비멍", "분위기맛집"],
        "texts": [
            "마침 비가 부슬부슬 내리는 날 갔는데 처마에서 빗방울 떨어지는 소리가 예술입니다... 비멍 때리다 시간 다 감.",
            "비 오는 날 한옥은 진짜 치트키네요. 흙냄새랑 젖은 나무 냄새가 묘하게 마음을 편하게 해줘요.",
            "비 와서 갈까 말까 고민했는데 안 왔으면 후회할 뻔! 빗소리 들으면서 마루에 앉아있으니 운치 최고예요.",
            "흐린 날씨라 사람도 별로 없고 운치 대박이었어요. 따뜻한 차 한 잔 마시면서 밖 바라보는데 영화 속 한 장면 같음.",
            "촉촉하게 젖은 디딤돌이랑 기와지붕 처마에서 똑똑 떨어지는 낙숫물 소리가 너무 로맨틱해요."
        ]
    },
    # 가족 & 부모님 동반 & 고택
    {
        "mood": "한적", "score": 1,
        "tags": ["부모님과", "가족여행", "역사체험", "고택"],
        "texts": [
            "부모님 모시고 효도 관광으로 다녀왔는데 두 분 다 너무 좋아하셔서 뿌듯했습니다. 나무 기둥 결 하나하나 멋스러워요.",
            "대청마루랑 마당 구조가 옛 선조들 지혜가 고스란히 묻어있네요. 어르신들 모시고 오기 정말 훌륭한 장소입니다.",
            "해설사 선생님 설명 들으면서 둘러보니까 그냥 지나칠 뻔한 기와 문양이나 디테일들이 다 다르게 보여요. 꼭 설명 들어보세요!",
            "아이들이랑 같이 방문했는데 책에서만 보던 전통 가옥 직접 만져보고 걸어볼 수 있어서 교육적으로도 참 유익했네요.",
            "고택 특유의 깊은 나무 향과 오랜 세월의 흔적이 참 고귀하게 느껴집니다. 가족 여행 코스로 딱이에요."
        ]
    },
    # 현실 꿀팁 & 방문 팁
    {
        "mood": "북적", "score": 2,
        "tags": ["방문팁", "산책", "솔직후기"],
        "texts": [
            "주차는 근처 공영주차장 이용하는 게 정신건강에 좋습니다 ㅋㅋㅋ 골목 안쪽은 차 절대 못 들어가요! 그래도 뷰는 최고.",
            "계단이나 언덕길이 조금 있어서 편한 운동화 필수입니다! 구두 신고 왔다가 발 아팠지만 풍경 보면서 극복함.",
            "낮 12시~2시는 햇빛이 꽤 강하니까 양산이나 모자 챙기세요. 해 질 녘 골목에 가로등 켜질 때가 진짜 낭만임.",
            "인기 포토존은 줄 좀 서야 하지만 회전율은 빨라요. 마당 구석 정자 쪽이 숨은 포토스팟입니다 다들 잘 모르더라구요!",
            "여름엔 모기기피제 살짝 챙기면 좋음 ㅎㅎ 그래도 마루에 불어오는 바람이 에어컨 바람보다 훨씬 상쾌하네요.",
            "주말 피크시간엔 사람 몰리니까 오전 10시쯤 가거나 5시 이후에 가시는 거 추천해요!"
        ]
    },
    # 차 한 잔 & 카페 & 산책로
    {
        "mood": "한적", "score": 1,
        "tags": ["전통차", "산책로", "데이트", "힐링"],
        "texts": [
            "산책 한 바퀴 돌고 바로 근처 한옥 찻집에서 오미자차 한 잔 마시니까 코스가 딱 완벽하네요. 주말 데이트 강추!",
            "골목길 따라 걷다가 구수한 냄새에 홀려서 전통 간식 사 먹었는데 꿀맛... 걷는 재미, 먹는 재미 다 잡을 수 있어요.",
            "주변 한옥 카페들이랑 묶어서 하루 코스로 다녀오기 정말 좋아요. 사진 백 장 넘게 찍고 갑니다 ㅋㅋㅋ",
            "동네 전체가 아기자기해서 발길 닿는 대로 들어가 본 작은 공방도 매력 있었어요. 기념품 하나씩 사 들고 옴.",
            "바람 솔솔 부는 마루에 앉아 따뜻한 대추차 마시니까 피로가 싹 풀려요."
        ]
    }
]

# 지역별 특화 템플릿
REGION_TEMPLATES = {
    '서울': [
        ("도심 한복판에 이런 고즈넉한 한옥 공간이 숨어있다는 게 새삼 신기해요. 빌딩 숲 벗어나서 잠깐 숨 돌리기 최고!", "한적", ["도심속힐링", "서울한옥", "산책"]),
        ("주말이라 북촌/서촌 나들이 온 분들 진짜 많네요! 그래도 골목길 골목길 구경하는 맛이 쏠쏠합니다.", "북적", ["서울나들이", "데이트", "주말골목"]),
        ("지하철역에서 조금 걸어 올라와야 하지만 올라온 보람이 있어요. 서울 전경이랑 기와지붕이 겹쳐 보이는 뷰가 장관!", "한적", ["전망맛집", "서울명소", "산책로"])
    ],
    '경기': [
        ("서울 근교로 드라이브 겸 주말 나들이 나왔는데 공기도 맑고 한옥도 단정해서 너무 좋았습니다.", "한적", ["근교드라이브", "주말나들이", "힐링"]),
        ("도심 근처인데도 숲이랑 한옥이 같이 어우러져 있어서 주말 힐링 스팟으로 딱이네요. 가족들이랑 또 올 듯!", "한적", ["가족나들이", "주말힐링", "숲속한옥"]),
        ("주말에 가족들이랑 가볍게 산책하러 왔어요. 마당도 넓고 아이들이 전통 놀이 체험도 할 수 있어서 만족스러웠습니다.", "북적", ["가족체험", "산책", "나들이"])
    ],
    '강원': [
        ("강원도 특유의 울창한 소나무 숲이랑 고택이 어우러져서 솔향기가 솔솔 납니다. 공기부터 달라요.", "한적", ["솔향기", "자연속한옥", "강원여행"]),
        ("바다 보러 왔다가 들렀는데 산 아래 한옥 정취가 이렇게 깊을 줄 몰랐어요. 조용히 머물다 가기 최고입니다.", "한적", ["고즈넉", "숨은명소", "힐링"]),
        ("뒤편에 병풍처럼 둘러싼 산세와 전통 가옥의 곡선이 한 폭의 동양화 같아요. 눈 정화 제대로 하고 갑니다.", "한적", ["풍경맛집", "자연", "힐링"])
    ],
    '충청': [
        ("백제의 고도답게 은은하고 소박한 멋이 살아있네요. 시끄럽지 않고 차분해서 천천히 걷기 참 좋습니다.", "한적", ["소박한멋", "역사산책", "고즈넉"]),
        ("충청도 특유의 여유로움과 단아한 한옥 마당이 너무 잘 어울려요. 툇마루에서 멍때리기 딱 좋은 명소!", "한적", ["여유", "툇마루", "힐링"]),
        ("근처 유적지랑 묶어서 둘러보기 딱 좋아요. 돌담길 흙냄새 맡으면서 걷는데 마음이 몽글몽글해짐.", "한적", ["돌담길", "산책", "역사체험"])
    ],
    '전라': [
        ("역시 맛과 멋의 고장답게 한옥 디테일도 화려하고 주변 먹거리까지 완벽합니다! 온종일 먹고 걷고 행복함.", "북적", ["전주한옥", "먹거리투어", "인생샷"]),
        ("대청마루의 넓은 개방감과 남도 한옥 특유의 따뜻한 온기가 참 인상 깊네요. 부모님도 대만족하셨어요.", "한적", ["남도정취", "부모님과", "고택"]),
        ("골목길 사이사이 전통 찻집이랑 공방 구경하느라 시간 가는 줄 몰랐네요. 한복 입고 사진 찍기 최고!", "북적", ["한복체험", "데이트", "사진맛집"])
    ],
    '경상': [
        ("수백 년 된 종택의 기품과 웅장함이 압도적입니다. 대청마루에 앉아있으면 세월의 무게가 그대로 전해져요.", "한적", ["종택", "역사체험", "고택명소"]),
        ("황리단길이나 안동 하회 쪽 고택들 구경하다 왔는데, 진짜 전통 한옥의 골격이 살아있어서 감탄 연발!", "북적", ["전통한옥", "명소투어", "데이트"]),
        ("돌담길을 따라 걷다 보면 마음이 절로 경건해지고 차분해집니다. 경상도 고택 여행의 진수를 맛보고 갑니다.", "한적", ["고즈넉", "사색", "힐링"])
    ],
    '제주': [
        ("제주 현무암 돌담과 한옥의 조화라니... 육지 한옥과는 또 다른 이국적이면서도 포근한 감성이에요!", "북적", ["제주한옥", "돌담감성", "이색명소"]),
        ("야자수랑 푸른 하늘, 그리고 기와지붕이 한 프레임에 담기는데 사진이 진짜 독특하고 예쁘게 나와요.", "북적", ["인생샷", "제주여행", "데이트"]),
        ("바람 많은 제주에서 만난 아늑한 한옥 쉼터. 마당 잔디밭에 앉아서 귤차 한 잔 마시니 천국이 따로 없네요.", "한적", ["제주힐링", "쉼터", "감성여행"])
    ]
}

def get_region_group(addr):
    if not addr:
        return '기타'
    if '서울' in addr:
        return '서울'
    if '경기' in addr or '인천' in addr:
        return '경기'
    if '강원' in addr:
        return '강원'
    if '충청' in addr or '충북' in addr or '충남' in addr or '대전' in addr or '세종' in addr:
        return '충청'
    if '전라' in addr or '전북' in addr or '전남' in addr or '광주' in addr:
        return '전라'
    if '경상' in addr or '경북' in addr or '경남' in addr or '대구' in addr or '부산' in addr or '울산' in addr:
        return '경상'
    if '제주' in addr:
        return '제주'
    return '기타'

# 4. 2,000건 리뷰 생성
# 54500700-0000-4000-9000-000000000501 ~ 2500
TOTAL_REVIEWS = 2000
reviews = []

# 장소 배분: 1080개 장소를 셔플하여 최소 1~2개씩 고르게 분배
shuffled_hanoks = list(hanoks)
random.shuffle(shuffled_hanoks)

# 장소 리스트를 2000개에 맞게 확장 (1080개 전원 최소 1번 포함 + 920개는 인기/랜덤 추가)
selected_hanok_pool = []
selected_hanok_pool.extend(shuffled_hanoks) # 1080개

# 나머지 920개는 전체에서 가중치 또는 랜덤으로 추가
remaining_count = TOTAL_REVIEWS - len(selected_hanok_pool)
selected_hanok_pool.extend(random.choices(shuffled_hanoks, k=remaining_count))
random.shuffle(selected_hanok_pool)

print(f"Total places pool prepared: {len(selected_hanok_pool)}")

for i in range(TOTAL_REVIEWS):
    rev_id_num = 501 + i
    rev_id = f"54500700-0000-4000-9000-{rev_id_num:012d}"
    user = random.choice(users)
    place = selected_hanok_pool[i]
    p_name = place['name']
    p_addr = place.get('address', '')
    reg_group = get_region_group(p_addr)

    # 템플릿 선택 (30% 확률로 지역 특화 템플릿, 70%는 일반 감성 템플릿)
    use_regional = (reg_group in REGION_TEMPLATES) and (random.random() < 0.35)
    
    if use_regional:
        cand = random.choice(REGION_TEMPLATES[reg_group])
        base_text, mood, tags_cand = cand[0], cand[1], cand[2]
        score = 1 if mood == '한적' else (1 if random.random() < 0.8 else 2)
    else:
        tpl_group = random.choice(COMMON_TEMPLATES)
        base_text = random.choice(tpl_group["texts"])
        mood = tpl_group["mood"]
        score = tpl_group["score"]
        tags_cand = tpl_group["tags"]

    # 장소명 자연스럽게 가미 (40% 확률로 앞에 부드러운 방문 맥락 문구 추가)
    if random.random() < 0.40:
        prefix_phrases = [
            f"'{p_name}' 다녀왔어요! ",
            f"{p_name} 처음 가봤는데 ",
            f"오랜만에 {p_name} 들렀는데 ",
            f"이번 여행 코스로 {p_name} 넣길 정말 잘했네요. ",
            f"SNS에서 보고 {p_name} 찾아왔는데 ",
            f"{p_name} 산책 겸 방문했습니다. ",
            f"가족들이랑 {p_name} 들렀어요. "
        ]
        text_content = random.choice(prefix_phrases) + base_text
    else:
        text_content = base_text

    # 태그 정리
    tags = list(tags_cand)
    if random.random() < 0.45:
        tags.append("온마루")
    if random.random() < 0.25 and reg_group != '기타':
        tags.append(f"{reg_group}한옥")

    # 생성 시간: 최근 30일 이내로 현실적 분산
    # 오늘 작성된 글(100개), 최근 3일(300개), 최근 7일(600개), 나머지 1000개
    if i < 100:
        # 최근 12시간 이내
        mins = random.randint(10, 720)
        created_at = now - datetime.timedelta(minutes=mins)
    elif i < 400:
        # 1~3일 전
        hours = random.randint(12, 72)
        created_at = now - datetime.timedelta(hours=hours, minutes=random.randint(0, 59))
    elif i < 1000:
        # 4~7일 전
        hours = random.randint(73, 168)
        created_at = now - datetime.timedelta(hours=hours, minutes=random.randint(0, 59))
    else:
        # 8~30일 전
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
        'text': text_content,
        'mood': mood,
        'score': score,
        'tags': tags,
        'created_at': created_at.strftime('%Y-%m-%dT%H:%M:%SZ')
    })

print(f"Generated {len(reviews)} reviews.")

# 5. SQL 파일 생성
sql_lines = [
    "-- ====================================================================",
    "-- OnMaru Warmth Reviews 2000 Seeding Script (Issue #765)",
    "-- Covers all 1,080 registered Hanoks across all Korean regions.",
    "-- Authentic traveler tone, realistic tips, and natural experiences.",
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
sql_lines.append("-- 3. Seed 2,000 Community Visit Reviews (Temp Table & Batch Insert)")
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

print(f"Successfully generated {output_file}")
