"""Conservative official filming candidate matching; never verifies a relation."""
import collections
import csv
import math
import re


def normalize_name(value):
    return re.sub(r'[^가-힣a-z0-9]', '', (value or '').lower())


def distance_meters(lat1, lon1, lat2, lon2):
    lat1, lon1, lat2, lon2 = map(math.radians, (lat1, lon1, lat2, lon2))
    delta_lat, delta_lon = lat2 - lat1, lon2 - lon1
    value = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 12742000 * math.asin(min(1, math.sqrt(value)))


def load_official(path):
    with open(path, encoding='cp949', newline='') as handle:
        rows = list(csv.DictReader(handle))
    index = collections.defaultdict(list)
    for row in rows:
        if row['미디어타입'] not in ('drama', 'movie'):
            continue
        name = normalize_name(row['장소명'])
        if name:
            index[name].append(row)
    return len(rows), index


def candidates(record, index):
    try:
        latitude, longitude = float(record['mapy']), float(record['mapx'])
    except (ValueError, TypeError, KeyError):
        return []
    result = []
    for row in index.get(normalize_name(record.get('title')), []):
        try:
            distance = distance_meters(latitude, longitude, float(row['위도']), float(row['경도']))
        except (ValueError, TypeError):
            continue
        if distance <= 30:
            result.append({
                'officialRow': row['연번'], 'workTitle': row['제목'],
                'mediaType': row['미디어타입'], 'officialPlaceName': row['장소명'],
                'distanceMeters': round(distance, 1),
            })
    return sorted(result, key=lambda item: (item['distanceMeters'], item['officialRow']))
