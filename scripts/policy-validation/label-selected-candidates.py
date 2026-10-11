#!/usr/bin/env python3
"""Label every selected TourAPI content ID without inventing filming claims.

Inputs and outputs contain public provider data. Keep production captures outside Git.
"""
import argparse
import hashlib
import importlib.util
import collections
import json
from pathlib import Path

POLICY_PATH = Path(__file__).with_name('dry-run-policy.py')
spec = importlib.util.spec_from_file_location('onmaru_dry_run_policy', POLICY_PATH)
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)
match_spec = importlib.util.spec_from_file_location('onmaru_official_match', Path(__file__).with_name('match-official-filming.py'))
matcher = importlib.util.module_from_spec(match_spec)
match_spec.loader.exec_module(matcher)


def canonical(record):
    return {key: record.get(key) for key in (
        'contentid', 'contenttypeid', 'title', 'addr1', 'areacode', 'sigungucode',
        'cat1', 'cat2', 'cat3', 'lclsSystm3', 'mapx', 'mapy', 'modifiedtime',
        'firstimage', 'cpyrhtDivCd')}


def label(capture, old_rows, official_index=None, official_row_count=0, verified_relations=None):
    candidates = {}
    provenance = collections.defaultdict(list)
    conflict_ids = set()
    for source in capture['captures']:
        if len(source['records']) != source['totalCount']:
            raise ValueError(f"incomplete capture: {source['operation']} {source['filter']}")
        for record in source['records']:
            content_id = str(record.get('contentid') or '')
            if not content_id.isdigit():
                raise ValueError('invalid contentId in provider capture')
            provenance[content_id].append(f"{source['operation']}:{source['filter']}")
            previous = candidates.setdefault(content_id, canonical(record))
            if previous != canonical(record):
                conflict_ids.add(content_id)
    old = {row['contentid']: row for row in old_rows}
    verified_relations = verified_relations or {}
    output = []
    counts = collections.Counter()
    official_relations = 0
    official_places = 0
    for content_id in sorted(candidates, key=lambda value: (len(value), value)):
        record = candidates[content_id]
        if content_id in conflict_ids:
            decision, reason, role = 'REVIEW', 'CONFLICTING_PROVIDER_PAYLOAD', None
        else:
            decision, reason, role = policy.classify(record)
        old_row = old.get(content_id)
        verified_count = verified_relations.get(content_id, 0)
        matched = matcher.candidates(record, official_index) if official_index is not None else []
        if matched and decision == 'EXCLUDE':
            decision, reason = 'REVIEW', 'OFFICIAL_FILMING_CANDIDATE_REQUIRES_POLICY_REVIEW'
        official_relations += len(matched)
        official_places += bool(matched)
        title = (record.get('title') or '').strip()
        address = (record.get('addr1') or '').strip()
        # A source-derived display line is safe; no model or filming inference is implied.
        display_summary = f"{title} · {address}" if address else title
        raw_hash = hashlib.sha256(json.dumps(record, sort_keys=True, ensure_ascii=False,
                                             separators=(',', ':')).encode()).hexdigest()
        output.append({
            'contentId': content_id, 'sourceHash': raw_hash,
            'sourceModifiedTime': record.get('modifiedtime'),
            'sourceTitle': title, 'sourceAddress': address,
            'sourceCode': record.get('lclsSystm3'),
            'policyVersion': policy.POLICY_VERSION,
            'decision': decision, 'reason': reason, 'role': role,
            'displaySummary': display_summary,
            'summarySource': 'TOURAPI_TITLE_ADDRESS',
            'tags': [role] if role else [],
            'kcontentsResearchStatus': 'VERIFIED_EXISTING' if verified_count else 'OFFICIAL_CANDIDATE_REVIEW' if matched else 'NO_OFFICIAL_MATCH_IN_DATASET' if official_index is not None else 'EVIDENCE_NOT_CHECKED',
            'officialFilmingCandidates': matched,
            'verifiedFilmingRelationCount': verified_count,
            'previouslyActive': bool(old_row and old_row.get('status') == 'ACTIVE'),
            'previousPlaceId': str(old_row.get('placeId')) if old_row and old_row.get('placeId') else None,
            'provenance': sorted(set(provenance[content_id])),
        })
        counts[decision] += 1
    manifest = {
        'capturedAt': capture['capturedAt'], 'policyVersion': policy.POLICY_VERSION,
        'captureCount': len(capture['captures']),
        'providerRows': sum(item['totalCount'] for item in capture['captures']),
        'uniqueContentIds': len(output), 'duplicateRows': sum(item['totalCount'] for item in capture['captures']) - len(output),
        'conflictingContentIds': len(conflict_ids), 'previousSnapshotRows': len(old),
        'officialSourceRows': official_row_count, 'officialCandidatePlaces': official_places,
        'officialCandidateRelations': official_relations,
        'existingVerifiedPlaces': sum(content_id in candidates for content_id in verified_relations),
        'existingVerifiedRelations': sum(count for content_id, count in verified_relations.items() if content_id in candidates),
        'verifiedOutsideCandidateContentIds': sorted(set(verified_relations) - set(candidates)),
        'previousOverlap': sum(row['contentId'] in old for row in output),
        'decisions': dict(sorted(counts.items())),
        'labelSetSha256': hashlib.sha256('\n'.join(row['contentId'] + ':' + row['sourceHash'] + ':' + row['decision']
                                                     for row in output).encode()).hexdigest(),
    }
    if sum(counts.values()) != manifest['uniqueContentIds']:
        raise AssertionError('label coverage mismatch')
    return output, manifest


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('capture')
    parser.add_argument('previous_snapshot')
    parser.add_argument('--official-csv')
    parser.add_argument('--verified-relations')
    parser.add_argument('--labels', required=True)
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args()
    capture = json.loads(Path(args.capture).read_text())
    old_rows = [json.loads(line) for line in Path(args.previous_snapshot).read_text().splitlines() if line]
    official_row_count, official_index = matcher.load_official(args.official_csv) if args.official_csv else (0, None)
    verified_relations = {row['contentId']: row['relationCount'] for row in
                          (json.loads(line) for line in Path(args.verified_relations).read_text().splitlines())} if args.verified_relations else {}
    rows, manifest = label(capture, old_rows, official_index, official_row_count, verified_relations)
    manifest['verifiedRelationsProvided'] = bool(args.verified_relations)
    if args.official_csv:
        manifest['officialSourceSha256'] = hashlib.sha256(Path(args.official_csv).read_bytes()).hexdigest()
    Path(args.labels).write_text(''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n'
                                         for row in rows))
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
