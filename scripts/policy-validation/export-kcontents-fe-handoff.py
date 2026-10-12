#!/usr/bin/env python3
"""Export source-grounded place summaries for FE review without publishing claims."""
import argparse
import collections
import hashlib
import json
from pathlib import Path


def export(labels, previous):
    public_ids = {str(row['contentid']): row.get('publicId') for row in previous}
    output = []
    for row in labels:
        audit = row['evidenceAudit']
        candidates = audit['nearbyOfficialPlaces'] + audit['distantExactNamePlaces']
        output.append({
            'placeId': public_ids.get(row['contentId']) or 'p-tourapi-' + row['contentId'],
            'sourceContentId': row['contentId'],
            'name': row['sourceTitle'],
            'placeSummaryPoints': row['placeSummaryPoints'],
            'candidateDecision': row['decision'],
            'researchStatus': audit['auditStatus'],
            'verifiedRelationCount': row['verifiedFilmingRelationCount'],
            'officialWorkResearchCandidates': [{
                'workTitle': work['workTitle'],
                'workType': work['workType'],
                'researchStatus': work['relationStatus'],
                'publiclyAvailable': False,
                'officialSourceRows': [source['officialRow'] for source in work['evidence']],
            } for work in row.get('officialWorkResearch', {}).get('works', [])],
            'officialResearchCandidates': [{
                'officialPlaceName': candidate['officialPlaceName'],
                'mediaTypes': candidate['mediaTypes'],
                'officialRows': candidate['officialRows'],
                'distanceMeters': candidate['distanceMeters'],
                'reviewRequired': True,
            } for candidate in candidates],
            'workArtwork': None,
        })
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('labels')
    parser.add_argument('previous_catalog')
    parser.add_argument('--output', required=True)
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args()
    labels = [json.loads(line) for line in Path(args.labels).read_text().splitlines()]
    previous = [json.loads(line) for line in Path(args.previous_catalog).read_text().splitlines()]
    existing_public_ids = {str(row['contentid']) for row in previous if row.get('publicId')}
    rows = export(labels, previous)
    encoded = ''.join(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n' for row in rows)
    Path(args.output).write_text(encoded)
    Path(args.output).chmod(0o600)
    manifest = {
        'rowCount': len(rows),
        'withExistingPublicPlaceId': sum(row['sourceContentId'] in existing_public_ids for row in rows),
        'withFallbackPublicPlaceId': sum(row['sourceContentId'] not in existing_public_ids for row in rows),
        'researchStatuses': dict(sorted(collections.Counter(row['researchStatus'] for row in rows).items())),
        'workResearchStatuses': dict(sorted(collections.Counter(
            work['researchStatus'] for row in rows for work in row['officialWorkResearchCandidates']).items())),
        'sha256': hashlib.sha256(encoded.encode()).hexdigest(),
        'publicationStatus': 'INTERNAL_REVIEW_ONLY',
    }
    Path(args.manifest).write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
    Path(args.manifest).chmod(0o600)
    print(json.dumps(manifest, ensure_ascii=False))


if __name__ == '__main__':
    main()
