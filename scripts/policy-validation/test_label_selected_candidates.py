import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).with_name('label-selected-candidates.py')
spec = importlib.util.spec_from_file_location('label_selected_candidates', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LabelSelectedCandidatesTests(unittest.TestCase):
    def test_labels_every_unique_id_and_never_invents_filming(self):
        row = {'contentid': '123', 'title': '북촌 한옥', 'addr1': '서울',
               'lclsSystm3': 'HS010100', 'mapx': '127', 'mapy': '37'}
        capture = {'capturedAt': '2026-10-12T00:00:00Z', 'captures': [
            {'operation': 'areaBasedList2', 'filter': 'HS010100', 'totalCount': 1, 'records': [row]},
            {'operation': 'searchKeyword2', 'filter': '한옥', 'totalCount': 1, 'records': [row]},
        ]}
        labels, manifest = module.label(capture, [])
        self.assertEqual(manifest['providerRows'], 2)
        self.assertEqual(manifest['uniqueContentIds'], 1)
        self.assertEqual(manifest['duplicateRows'], 1)
        self.assertEqual(labels[0]['decision'], 'INCLUDE')
        self.assertEqual(labels[0]['kcontentsResearchStatus'], 'EVIDENCE_NOT_CHECKED')
        self.assertEqual(labels[0]['verifiedFilmingRelationCount'], 0)
        self.assertNotIn('workTitle', labels[0])

    def test_conflicting_provider_rows_require_review(self):
        base = {'contentid': '123', 'title': '북촌 한옥', 'lclsSystm3': 'HS010100',
                'mapx': '127', 'mapy': '37'}
        capture = {'capturedAt': '2026-10-12T00:00:00Z', 'captures': [
            {'operation': 'areaBasedList2', 'filter': 'HS010100', 'totalCount': 1, 'records': [base]},
            {'operation': 'searchKeyword2', 'filter': '한옥', 'totalCount': 1,
             'records': [base | {'title': '다른 장소'}]},
        ]}
        labels, manifest = module.label(capture, [])
        self.assertEqual(manifest['conflictingContentIds'], 1)
        self.assertEqual(labels[0]['decision'], 'REVIEW')
        self.assertEqual(labels[0]['reason'], 'CONFLICTING_PROVIDER_PAYLOAD')

    def test_incomplete_capture_fails(self):
        with self.assertRaisesRegex(ValueError, 'incomplete capture'):
            module.label({'capturedAt': 'now', 'captures': [
                {'operation': 'areaBasedList2', 'filter': 'HS010100', 'totalCount': 2,
                 'records': [{'contentid': '123'}]},
            ]}, [])


if __name__ == '__main__':
    unittest.main()
