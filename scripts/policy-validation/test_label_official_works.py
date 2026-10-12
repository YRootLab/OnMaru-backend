import importlib.util
from pathlib import Path
import unittest


PATH = Path(__file__).with_name('label-official-works.py')
spec = importlib.util.spec_from_file_location('label_official_works', PATH)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class OfficialWorkLabelTests(unittest.TestCase):
    def label(self, distance=20):
        return {'contentId': '126508', 'sourceTitle': '경복궁', 'sourceAddress': '서울특별시 종로구 사직로 161',
                'evidenceAudit': {'nearbyOfficialPlaces': [{
                    'officialPlaceName': '경복궁', 'distanceMeters': distance,
                    'officialRows': ['1', '2', '3']}], 'distantExactNamePlaces': []}}

    def sources(self):
        base = {'장소명': '경복궁', '주소': '서울특별시 종로구 사직로 161',
                '장소설명': '2회에서 주인공이 광화문을 지나간다.'}
        return {'1': base | {'제목': '도깨비', '미디어타입': 'drama'},
                '2': base | {'제목': '방탄소년단', '미디어타입': 'artist'},
                '3': base | {'제목': '1박2일 시즌4', '미디어타입': 'show'}}

    def test_source_work_types_and_artist_name_boundary(self):
        works = module.work_candidates(self.label(), self.sources())
        self.assertEqual({(w['workType'], w['workTitle']) for w in works},
                         {('DRAMA', '도깨비'), ('VARIETY', '1박2일 시즌4')})
        self.assertTrue(all(w['relationStatus'] == 'SOURCE_PLACE_MATCH' for w in works))

    def test_far_or_different_address_stays_in_review(self):
        self.assertTrue(all(w['relationStatus'] == 'REVIEW_REQUIRED'
                            for w in module.work_candidates(self.label(300), self.sources())))
        sources = self.sources()
        sources['1']['주소'] = '서울특별시 종로구 사직로 162'
        drama = next(w for w in module.work_candidates(self.label(), sources) if w['workType'] == 'DRAMA')
        self.assertEqual(drama['relationStatus'], 'REVIEW_REQUIRED')

    def test_curated_music_video_title_uses_description_not_artist_name(self):
        label = self.label()
        label['evidenceAudit']['nearbyOfficialPlaces'][0]['officialRows'] = ['5369']
        sources = {'5369': {'장소명': '경복궁', '주소': '서울특별시 종로구 사직로 161',
                            '장소설명': "BTS의 'Save Me' 뮤직비디오 촬영지",
                            '제목': '방탄소년단', '미디어타입': 'artist'}}
        works = module.work_candidates(label, sources)
        self.assertEqual([(w['workType'], w['workTitle']) for w in works],
                         [('MUSIC_VIDEO', 'Save Me')])

    def test_attach_requires_complete_identical_content_ids(self):
        labels = [{'contentId': '1'}]
        works = [{'contentId': '1', 'works': []}]
        self.assertEqual(module.attach_work_labels(labels, works, {'placeCount': 1})[0]['officialWorkResearch'], works[0])
        with self.assertRaisesRegex(ValueError, 'contentId/order'):
            module.attach_work_labels(labels, [{'contentId': '2', 'works': []}], {'placeCount': 1})

    def test_curated_alias_still_requires_same_road_address(self):
        label = self.label()
        label['contentId'] = '126504'
        label['sourceTitle'] = '봉은사(서울)'
        label['evidenceAudit']['nearbyOfficialPlaces'][0]['officialPlaceName'] = '봉은사'
        sources = self.sources()
        for source in sources.values():
            source['장소명'] = '봉은사'
        result = module.work_candidates(label, sources)
        self.assertEqual(result[0]['evidence'][0]['identityCheck'], 'ROAD_ADDRESS_AND_CURATED_ALIAS')
        sources['1']['주소'] = '서울특별시 종로구 사직로 162'
        drama = next(w for w in module.work_candidates(label, sources) if w['workType'] == 'DRAMA')
        self.assertEqual(drama['relationStatus'], 'REVIEW_REQUIRED')


if __name__ == '__main__':
    unittest.main()
