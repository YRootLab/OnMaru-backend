import urllib.request
import urllib.parse
import json

KEYWORDS = [
    '북촌', '종로', '서촌', '은평', '남산', '전주', '경주', '안동', '강릉', '수원',
    '제주', '대구', '부여', '공주', '남원', '순천', '통영', '담양', '영주', '청송',
    '인사동', '황리단길', '하회마을', '양동마을', '한옥'
]

places_dict = {}

headers = {'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)'}

for kw in KEYWORDS:
    url = f"https://api.onmaru.site/api/v1/hanoks?keyword={urllib.parse.quote(kw)}&limit=50"
    try:
        req = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(req, timeout=10) as resp:
            data = json.loads(resp.read().decode('utf-8'))
            items = data.get('items', [])
            for item in items:
                pid = item.get('placeId')
                if pid and pid not in places_dict:
                    places_dict[pid] = item
    except Exception as e:
        print(f"Error fetching {kw}: {e}")

print(f"Total unique places gathered: {len(places_dict)}")

with open("scripts/seed-reviews/places.json", "w", encoding="utf-8") as f:
    json.dump(list(places_dict.values()), f, ensure_ascii=False, indent=2)

