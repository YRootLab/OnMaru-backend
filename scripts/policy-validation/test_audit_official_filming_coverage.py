import collections
import csv
import importlib.util
from pathlib import Path
import tempfile
import unittest


PATH = Path(__file__).with_name('audit-official-filming-coverage.py')
spec = importlib.util.spec_from_file_location('audit_official_filming_coverage', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OfficialFilmingCoverageTests(unittest.TestCase):
    def setUp(self):
        self.record = {'contentid': '1', 'title': '한국민속촌', 'mapy': '37.259', 'mapx': '127.117'}
        self.grid = collections.defaultdict(list)
        self.by_name = collections.defaultdict(list)

    def add(self, name, lat, lon, row_id='7'):
        row = {'장소명': name, '제목': '작품', '미디어타입': 'drama', '주소': '경기도 용인시', '위도': str(lat),
               '경도': str(lon), '연번': row_id}
        located = (lat, lon, row)
        self.grid[(int(lat * 100), int(lon * 100))].append(located)
        self.by_name[module.normalized(name)].append(located)

    def test_same_name_within_100m_is_a_review_candidate(self):
        self.add('한국 민속촌', 37.2592, 127.117)
        result = module.audit(self.record, self.grid, self.by_name)
        self.assertEqual(result['auditStatus'], 'EXACT_NAME_NEARBY_REVIEW')
        self.assertEqual(result['nearbyOfficialPlaces'][0]['officialRows'], ['7'])

    def test_large_site_same_name_at_600m_is_still_found(self):
        self.add('한국민속촌', 37.2644, 127.117)
        result = module.audit(self.record, self.grid, self.by_name)
        self.assertEqual(result['auditStatus'], 'EXACT_NAME_WITHIN_1KM_REVIEW')
        self.assertEqual(len(result['distantExactNamePlaces']), 1)

    def test_nearby_different_place_is_never_verified(self):
        self.add('한국민속촌 내부 카페', 37.2591, 127.117)
        result = module.audit(self.record, self.grid, self.by_name)
        self.assertEqual(result['auditStatus'], 'NAME_OVERLAP_NEARBY_REVIEW')
        self.assertNotEqual(result['auditStatus'], 'VERIFIED_EXISTING')

    def test_missing_source_match_and_coordinate_are_distinct(self):
        self.assertEqual(module.audit(self.record, self.grid, self.by_name)['auditStatus'],
                         'NO_NEARBY_MATCH_IN_DATASET')
        invalid = self.record | {'mapy': None}
        self.assertEqual(module.audit(invalid, self.grid, self.by_name)['auditStatus'],
                         'COORDINATE_UNAVAILABLE')

    def test_existing_verified_relation_takes_precedence(self):
        self.assertEqual(module.audit(self.record, self.grid, self.by_name, True)['auditStatus'],
                         'VERIFIED_EXISTING')

    def test_attach_requires_exact_order_and_source(self):
        labels = [{'contentId': '1'}]
        manifest = {'uniqueContentIds': 1, 'officialSourceSha256': 'a'}
        audits = [{'contentId': '1', 'auditStatus': 'NO_NEARBY_MATCH_IN_DATASET'}]
        audit_manifest = {'candidateCount': 1, 'officialSourceSha256': 'a',
                          'auditVersion': 'v1', 'auditSha256': 'b',
                          'statuses': {'NO_NEARBY_MATCH_IN_DATASET': 1}}
        enriched, result = module.attach_audits(labels, manifest, audits, audit_manifest)
        self.assertEqual(enriched[0]['evidenceAudit'], audits[0])
        self.assertEqual(result['evidenceAuditSha256'], 'b')
        with self.assertRaisesRegex(ValueError, 'contentId differs'):
            module.attach_audits(labels, manifest, [{'contentId': '2'}], audit_manifest)
        with self.assertRaisesRegex(ValueError, 'official source differs'):
            module.attach_audits(labels, manifest, audits, audit_manifest | {'officialSourceSha256': 'c'})

    def test_show_and_artist_are_included_without_treating_title_as_a_work(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'official.csv'
            with path.open('w', encoding='cp949', newline='') as handle:
                writer = csv.DictWriter(handle, fieldnames=['미디어타입', '장소명', '제목', '주소',
                                                             '위도', '경도', '연번'])
                writer.writeheader()
                writer.writerow({'미디어타입': 'show', '장소명': '한국민속촌', '제목': '예능',
                                 '주소': '용인', '위도': '37.259', '경도': '127.117', '연번': '1'})
                writer.writerow({'미디어타입': 'artist', '장소명': '한국민속촌', '제목': '가수',
                                 '주소': '용인', '위도': '37.259', '경도': '127.117', '연번': '2'})
            source_count, usable_count, grid, by_name = module.official_index(path)
            self.assertEqual((source_count, usable_count), (2, 2))
            result = module.audit(self.record, grid, by_name)
            self.assertEqual(result['nearbyOfficialPlaces'][0]['mediaTypes'], ['show', 'artist'])
            self.assertEqual(result['nearbyOfficialPlaces'][0]['sourceTitles'], ['예능', '가수'])


if __name__ == '__main__':
    unittest.main()
