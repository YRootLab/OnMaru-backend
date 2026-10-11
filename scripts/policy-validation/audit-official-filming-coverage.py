#!/usr/bin/env python3
"""Audit every selected place against one official filming dataset.

The absence of a match in this dataset is not evidence that a place was never filmed.
All newly found matches remain review candidates, never verified relationships.
"""
import argparse
import collections
import csv
import hashlib
import json
import math
import re
from pathlib import Path


AUDIT_VERSION = 'official-filming-spatial-v1.0.0'
MAX_DISTANCE_METERS = 100
MAX_EXACT_NAME_DISTANCE_METERS = 1000


def normalized(value):
    return re.sub(r'[^가-힣a-z0-9]', '', (value or '').lower())


def distance_meters(lat1, lon1, lat2, lon2):
    lat1, lon1, lat2, lon2 = map(math.radians, (lat1, lon1, lat2, lon2))
    delta_lat, delta_lon = lat2 - lat1, lon2 - lon1
    haversine = math.sin(delta_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(delta_lon / 2) ** 2
    return 12742000 * math.asin(min(1, math.sqrt(haversine)))


def official_index(path):
    with open(path, encoding='cp949', newline='') as handle:
        rows = list(csv.DictReader(handle))
    grid = collections.defaultdict(list)
    by_name = collections.defaultdict(list)
    valid = 0
    for row in rows:
        if row['미디어타입'] not in ('drama', 'movie', 'show', 'artist'):
            continue
        try:
            latitude, longitude = float(row['위도']), float(row['경도'])
        except (TypeError, ValueError):
            continue
        if not (33 <= latitude <= 39 and 124 <= longitude <= 132):
            continue
        located = (latitude, longitude, row)
        grid[(int(latitude * 100), int(longitude * 100))].append(located)
        if normalized(row['장소명']):
            by_name[normalized(row['장소명'])].append(located)
        valid += 1
    return len(rows), valid, grid, by_name


def audit(record, grid, by_name, verified=False):
    content_id = str(record['contentid'])
    title = record.get('title') or ''
    try:
        latitude, longitude = float(record['mapy']), float(record['mapx'])
    except (TypeError, ValueError, KeyError):
        latitude, longitude = 0, 0
    if not (33 <= latitude <= 39 and 124 <= longitude <= 132):
        return {'contentId': content_id, 'auditStatus': 'COORDINATE_UNAVAILABLE',
                'auditVersion': AUDIT_VERSION, 'nearbyOfficialPlaces': [],
                'distantExactNamePlaces': []}
    nearby = {}
    lat_cell, lon_cell = int(latitude * 100), int(longitude * 100)
    for lat_grid in range(lat_cell - 1, lat_cell + 2):
        for lon_grid in range(lon_cell - 1, lon_cell + 2):
            for source_lat, source_lon, row in grid[(lat_grid, lon_grid)]:
                distance = distance_meters(latitude, longitude, source_lat, source_lon)
                if distance > MAX_DISTANCE_METERS:
                    continue
                name = normalized(row['장소명'])
                key = (name, row['위도'], row['경도'])
                title_name = normalized(title)
                same_name = bool(name and name == title_name)
                name_overlap = bool(name and title_name and min(len(name), len(title_name)) >= 4
                                    and (name in title_name or title_name in name))
                old = nearby.get(key)
                if old is None:
                    nearby[key] = {'officialPlaceName': row['장소명'],
                                   'officialAddress': row['주소'],
                                   'distanceMeters': round(distance, 1),
                                   'sameName': same_name, 'nameOverlap': name_overlap,
                                   'officialRows': [row['연번']],
                                   'sourceTitles': [row['제목']],
                                   'mediaTypes': [row['미디어타입']]}
                else:
                    old['officialRows'].append(row['연번'])
                    if row['제목'] not in old['sourceTitles']:
                        old['sourceTitles'].append(row['제목'])
                    if row['미디어타입'] not in old['mediaTypes']:
                        old['mediaTypes'].append(row['미디어타입'])
    places = sorted(nearby.values(), key=lambda row: (not row['sameName'], not row['nameOverlap'],
                                                      row['distanceMeters'], row['officialPlaceName']))
    distant = {}
    if not any(place['sameName'] for place in places):
        for source_lat, source_lon, row in by_name.get(normalized(title), []):
            distance = distance_meters(latitude, longitude, source_lat, source_lon)
            if MAX_DISTANCE_METERS < distance <= MAX_EXACT_NAME_DISTANCE_METERS:
                key = (normalized(row['장소명']), row['위도'], row['경도'])
                old = distant.get(key)
                if old is None:
                    distant[key] = {'officialPlaceName': row['장소명'],
                                    'officialAddress': row['주소'],
                                    'distanceMeters': round(distance, 1),
                                    'officialRows': [row['연번']],
                                    'sourceTitles': [row['제목']],
                                    'mediaTypes': [row['미디어타입']]}
                else:
                    old['officialRows'].append(row['연번'])
                    if row['제목'] not in old['sourceTitles']:
                        old['sourceTitles'].append(row['제목'])
                    if row['미디어타입'] not in old['mediaTypes']:
                        old['mediaTypes'].append(row['미디어타입'])
    distant_places = sorted(distant.values(), key=lambda row: (row['distanceMeters'], row['officialPlaceName']))
    if verified:
        status = 'VERIFIED_EXISTING'
    elif any(place['sameName'] for place in places):
        status = 'EXACT_NAME_NEARBY_REVIEW'
    elif distant_places:
        status = 'EXACT_NAME_WITHIN_1KM_REVIEW'
    elif any(place['nameOverlap'] for place in places):
        status = 'NAME_OVERLAP_NEARBY_REVIEW'
    elif places:
        status = 'COLOCATED_NEARBY_REVIEW'
    else:
        status = 'NO_NEARBY_MATCH_IN_DATASET'
    return {'contentId': content_id, 'auditStatus': status, 'auditVersion': AUDIT_VERSION,
            'nearbyOfficialPlaces': places, 'distantExactNamePlaces': distant_places}


def run(capture, labels, official_csv):
    source_rows, usable_rows, grid, by_name = official_index(official_csv)
    records = {str(row['contentid']): row for item in capture['captures'] for row in item['records']}
    if len(records) != len(labels) or set(records) != {row['contentId'] for row in labels}:
        raise ValueError('capture and label contentId sets differ')
    audits = [audit(records[row['contentId']], grid, by_name, row['verifiedFilmingRelationCount'] > 0)
              for row in labels]
    counts = dict(sorted(collections.Counter(row['auditStatus'] for row in audits).items()))
    encoded = ''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n' for row in audits)
    manifest = {'auditVersion': AUDIT_VERSION, 'candidateCount': len(audits),
                'officialSourceRows': source_rows, 'usableMediaRows': usable_rows,
                'officialSourceSha256': hashlib.sha256(Path(official_csv).read_bytes()).hexdigest(),
                'auditSha256': hashlib.sha256(encoded.encode()).hexdigest(),
                'statuses': counts}
    return encoded, manifest


def attach_audits(labels, label_manifest, audits, audit_manifest):
    if len(labels) != len(audits) or len(labels) != audit_manifest['candidateCount']:
        raise ValueError('audit coverage differs from labels')
    if any(label['contentId'] != audit['contentId'] for label, audit in zip(labels, audits)):
        raise ValueError('audit order or contentId differs from labels')
    if label_manifest['uniqueContentIds'] != len(labels):
        raise ValueError('label manifest count differs from audits')
    if label_manifest['officialSourceSha256'] != audit_manifest['officialSourceSha256']:
        raise ValueError('official source differs between labels and audits')
    enriched = [label | {'evidenceAudit': audit} for label, audit in zip(labels, audits)]
    enriched_manifest = label_manifest | {
        'evidenceAuditVersion': audit_manifest['auditVersion'],
        'evidenceAuditSha256': audit_manifest['auditSha256'],
        'evidenceAuditStatuses': audit_manifest['statuses'],
    }
    return enriched, enriched_manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('capture')
    parser.add_argument('labels')
    parser.add_argument('official_csv')
    parser.add_argument('--audits', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--enriched-labels', required=True)
    parser.add_argument('--enriched-manifest', required=True)
    parser.add_argument('--label-manifest', required=True)
    args = parser.parse_args()
    capture = json.loads(Path(args.capture).read_text())
    labels = [json.loads(line) for line in Path(args.labels).read_text().splitlines()]
    encoded, manifest = run(capture, labels, args.official_csv)
    audits = [json.loads(line) for line in encoded.splitlines()]
    label_manifest = json.loads(Path(args.label_manifest).read_text())
    enriched, enriched_manifest = attach_audits(labels, label_manifest, audits, manifest)
    Path(args.audits).write_text(encoded)
    Path(args.audits).chmod(0o600)
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    Path(args.manifest).chmod(0o600)
    Path(args.enriched_labels).write_text(''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n'
                                                  for row in enriched))
    Path(args.enriched_labels).chmod(0o600)
    Path(args.enriched_manifest).write_text(json.dumps(enriched_manifest, ensure_ascii=False, indent=2) + '\n')
    Path(args.enriched_manifest).chmod(0o600)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
