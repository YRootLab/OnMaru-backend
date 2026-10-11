import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).with_name('match-official-filming.py')
spec = importlib.util.spec_from_file_location('match_official_filming', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class MatchOfficialFilmingTests(unittest.TestCase):
    def test_exact_name_and_nearby_coordinates_only(self):
        official = {'연번': '7', '제목': '작품', '미디어타입': 'drama',
                    '장소명': '경복궁', '위도': '37.5796', '경도': '126.9770'}
        index = {module.normalize_name('경복궁'): [official]}
        actual = module.candidates({'title': '경복궁', 'mapy': '37.5796', 'mapx': '126.9770'}, index)
        self.assertEqual([row['officialRow'] for row in actual], ['7'])
        self.assertEqual(module.candidates({'title': '경복궁 앞', 'mapy': '37.5796', 'mapx': '126.9770'}, index), [])
        self.assertEqual(module.candidates({'title': '경복궁', 'mapy': '37.5900', 'mapx': '126.9770'}, index), [])


if __name__ == '__main__':
    unittest.main()
