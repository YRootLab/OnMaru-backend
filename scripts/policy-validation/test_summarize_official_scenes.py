import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).with_name('summarize-official-scenes.py')
spec = importlib.util.spec_from_file_location('summarize_official_scenes', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SummarizeOfficialScenesTests(unittest.TestCase):
    def test_accepts_only_an_exact_source_quote(self):
        source = [{'key': '1:7', 'contentId': '1', 'officialRow': '7', 'work': '작품',
                   'description': '이 장소에서 주인공이 친구를 만나는 장면이 나온다.'}]
        valid = module.validate_batch(source, [{'key': '1:7', 'summary': '주인공이 친구를 만난다.',
                                                'sourceQuote': '주인공이 친구를 만나는 장면이 나온다.'}], 'a' * 64)
        self.assertEqual(valid[0]['status'], 'REVIEW_REQUIRED')
        invalid = module.validate_batch(source, [{'key': '1:7', 'summary': '배우가 노래한다.',
                                                  'sourceQuote': '배우가 노래한다.'}], 'a' * 64)
        self.assertEqual(invalid[0]['status'], 'MODEL_OUTPUT_REJECTED')
        self.assertEqual(invalid[0]['summaryCandidate'], '')

    def test_rejects_missing_model_keys(self):
        source = [{'key': '1:7', 'contentId': '1', 'officialRow': '7', 'work': '작품', 'description': '장면'}]
        with self.assertRaisesRegex(ValueError, 'omitted'):
            module.validate_batch(source, [], 'a' * 64)


if __name__ == '__main__':
    unittest.main()
