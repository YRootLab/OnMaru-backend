#!/usr/bin/env python3
"""Link selected TourAPI places to source-grounded work titles for review.

The official CSV's artist title is an artist name, never a work title.
No output of this script by itself approves a public relation.
"""
import argparse
import collections
import csv
import hashlib
import json
import re
from pathlib import Path


VERSION = 'official-works-place-identity-v1.3.0'
WORK_TYPES = {'drama': 'DRAMA', 'movie': 'MOVIE', 'show': 'VARIETY', 'artist': 'ARTIST'}
MAX_DISTANCE_METERS = 1000
MAX_AUTO_MATCH_DISTANCE_METERS = 250
MV_TITLES = {
    '2773': '시월에 설악산', '2781': '그 길에서', '10693': '여름비',
    '3598': 'World U', '10697': '귀를 기울이면', '3567': 'PSYCHO',
    '10526': '꽃잎점', '14262': '걸어본다', '14286': 'L.U.V',
    '8712': '바보라도 알아', '10694': '시간을 달려서',
    '3453': 'Candy Jelly Love', '10518': 'Secret Garden',
    '5996': '헤어져줘서 고마워', '8207': 'Bittersweet',
    '5369': 'Save Me', '10055': '잠 못 드는 밤 비는 내리고',
}
PLACE_NAME_ALIASES = {
    '125754': {'신흥사'}, '125772': {'월정사'}, '125800': {'선교장'},
    '126173': {'청량사'}, '126374': {'소쇄원'}, '126504': {'봉은사'},
    '127037': {'송천떡마을'}, '127558': {'진관사'},
    '127664': {'최참판댁 촬영지'}, '128812': {'정수사'},
    '129471': {'대조사'}, '132182': {'평화시장'},
    '132532': {'서문시장'}, '317559': {'사성암'},
    '945987': {'홍난파가옥'}, '1607286': {'문화공감수정'},
    '1693628': {'보성여관'}, '1704536': {'세연정'},
    '1796079': {'성심당 본점'}, '1997221': {'감천문화마을'},
    '2605429': {'목월빵집'}, '2700971': {'락고재 서울'},
    '2745648': {'이달엔영월'}, '2753045': {'서연의 집'},
    '2763775': {'이천도자예술마을'}, '2764311': {'필무드'},
    '2764524': {'앤드테라스 파주점'}, '2774306': {'아다마스253'},
    '2775583': {'346커피스토리 (구 커피스토리)'},
    '2783358': {'테르트르'}, '2783406': {'카페델문도'},
    '2789674': {'일영허브랜드'},
}
MANUAL_REVIEW_ROW_REASONS = {
    '11827': 'description only describes architecture, not a filming appearance',
    '2962': 'description refers to a prop exhibition, not filming at this place',
    '10283': 'description only describes a former station and exhibition space',
    '11901': 'description recounts dialogue without establishing this location',
    '5430': 'description only describes a cultural experience village',
}
NEGATED_FILMING = re.compile(r'촬영지가\s*(?:아니|아닌)|촬영하지\s*않')


def normalized(value):
    return re.sub(r'[^가-힣a-z0-9]', '', (value or '').lower())


def road_address(value):
    matches = re.findall(r'([가-힣0-9·]+(?:대로|로|길))\s*(\d+(?:-\d+)?)', value or '')
    return matches[-1] if matches else None


def source_rows(path):
    with open(path, encoding='cp949', newline='') as handle:
        rows = {row['연번']: row for row in csv.DictReader(handle)}
    for row_id, title in MV_TITLES.items():
        row = rows.get(row_id)
        if (not row or row['미디어타입'] != 'artist'
                or normalized(title) not in normalized(row['장소설명'])
                or not ('뮤직비디오' in row['장소설명'] or 'MV' in row['장소설명'])):
            raise ValueError(f'curated MV title no longer supported by official row {row_id}')
    return rows


def work_candidates(label, sources):
    audit = label['evidenceAudit']
    result = collections.defaultdict(list)
    for place in audit['nearbyOfficialPlaces'] + audit['distantExactNamePlaces']:
        exact_name = normalized(place['officialPlaceName']) == normalized(label['sourceTitle'])
        curated_alias = (place['officialPlaceName'] in PLACE_NAME_ALIASES.get(label['contentId'], set())
                         and place['distanceMeters'] <= 50)
        if not (exact_name or curated_alias):
            continue
        if place['distanceMeters'] > MAX_DISTANCE_METERS:
            continue
        for row_id in place['officialRows']:
            source = sources[row_id]
            if normalized(source['장소명']) != normalized(place['officialPlaceName']):
                raise ValueError(f'audit source place drift for official row {row_id}')
            media = source['미디어타입']
            if media not in WORK_TYPES and row_id not in MV_TITLES:
                continue
            title = MV_TITLES[row_id] if row_id in MV_TITLES else source['제목'].strip()
            work_type = 'MUSIC_VIDEO' if row_id in MV_TITLES else WORK_TYPES[media]
            key = (work_type, normalized(title))
            if not key[1]:
                continue
            address = road_address(label['sourceAddress'])
            source_address = road_address(source['주소'])
            exact_address = bool(address and address == source_address)
            description = source['장소설명'].strip()
            result[key].append({
                'officialRow': row_id, 'workTitle': title, 'workType': work_type,
                'officialPlaceName': source['장소명'], 'officialAddress': source['주소'],
                'sourceArtistName': source['제목'].strip() if media == 'artist' else None,
                'sourceDescription': description, 'distanceMeters': place['distanceMeters'],
                'identityCheck': ('ROAD_ADDRESS_AND_NAME' if exact_name and exact_address
                                  else 'ROAD_ADDRESS_AND_CURATED_ALIAS' if curated_alias and exact_address
                                  else 'NAME_AND_NEARBY_COORDINATE_ONLY'),
                'relationStatus': ('SOURCE_PLACE_MATCH' if exact_address and description
                                   and place['distanceMeters'] <= MAX_AUTO_MATCH_DISTANCE_METERS
                                   and row_id not in MANUAL_REVIEW_ROW_REASONS
                                   and not NEGATED_FILMING.search(description)
                                   else 'REVIEW_REQUIRED'),
                'reviewReason': MANUAL_REVIEW_ROW_REASONS.get(row_id),
            })
    works = []
    for key, evidence in sorted(result.items()):
        evidence.sort(key=lambda row: int(row['officialRow']))
        strong = [row for row in evidence if row['relationStatus'] == 'SOURCE_PLACE_MATCH']
        works.append({
            'workTitle': evidence[0]['workTitle'], 'workType': key[0],
            'relationStatus': 'SOURCE_PLACE_MATCH' if strong else 'REVIEW_REQUIRED',
            'evidence': evidence,
            'sourceUrl': 'https://www.data.go.kr/data/15111405/fileData.do',
        })
    return works


def run(labels, sources):
    output = []
    for label in labels:
        works = work_candidates(label, sources)
        output.append({'contentId': label['contentId'],
                       'sourceTitle': label['sourceTitle'],
                       'candidateDecision': label['decision'],
                       'works': works,
                       'artistRowsForSeparateReview': sorted({row_id for place in
                           label['evidenceAudit']['nearbyOfficialPlaces'] + label['evidenceAudit']['distantExactNamePlaces']
                           for row_id in place['officialRows'] if sources[row_id]['미디어타입'] == 'artist'
                           and row_id not in MV_TITLES},
                                                              key=int)})
    return output


def attach_work_labels(labels, work_rows, manifest):
    if len(labels) != len(work_rows) or len(labels) != manifest['placeCount']:
        raise ValueError('work label coverage differs from selected labels')
    if any(label['contentId'] != works['contentId'] for label, works in zip(labels, work_rows)):
        raise ValueError('work label contentId/order differs from selected labels')
    return [label | {'officialWorkResearch': works} for label, works in zip(labels, work_rows)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('labels')
    parser.add_argument('official_csv')
    parser.add_argument('--output', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--enriched-labels')
    parser.add_argument('--enriched-manifest')
    parser.add_argument('--label-manifest')
    args = parser.parse_args()
    labels = [json.loads(line) for line in Path(args.labels).read_text().splitlines()]
    rows = run(labels, source_rows(args.official_csv))
    encoded = ''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n' for row in rows)
    Path(args.output).write_text(encoded)
    Path(args.output).chmod(0o600)
    statuses = collections.Counter(work['relationStatus'] for row in rows for work in row['works'])
    manifest = {'policyVersion': VERSION, 'placeCount': len(rows),
                'placesWithWorks': sum(bool(row['works']) for row in rows),
                'workRelationStatuses': dict(sorted(statuses.items())),
                'artistContextPlaces': sum(bool(row['artistRowsForSeparateReview']) for row in rows),
                'officialSourceSha256': hashlib.sha256(Path(args.official_csv).read_bytes()).hexdigest(),
                'workLabelSha256': hashlib.sha256(encoded.encode()).hexdigest()}
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    Path(args.manifest).chmod(0o600)
    if args.enriched_labels and not (args.enriched_manifest and args.label_manifest):
        raise ValueError('both enriched manifest and label manifest are required')
    if args.enriched_labels:
        enriched = attach_work_labels(labels, rows, manifest)
        Path(args.enriched_labels).write_text(''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n'
                                                  for row in enriched))
        Path(args.enriched_labels).chmod(0o600)
        previous_manifest = json.loads(Path(args.label_manifest).read_text())
        previous_manifest.update({'officialWorkPolicyVersion': VERSION,
                                  'officialWorkLabelSha256': manifest['workLabelSha256'],
                                  'officialWorkRelationStatuses': manifest['workRelationStatuses']})
        Path(args.enriched_manifest).write_text(json.dumps(previous_manifest, ensure_ascii=False, indent=2) + '\n')
        Path(args.enriched_manifest).chmod(0o600)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
