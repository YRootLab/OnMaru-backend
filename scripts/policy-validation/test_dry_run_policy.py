import importlib.util
import pathlib
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("dry-run-policy.py")
SPEC = importlib.util.spec_from_file_location("dry_run_policy", MODULE_PATH)
policy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(policy)


def place(content_id, code, title):
    return {
        "contentid": content_id,
        "lclsSystm3": code,
        "title": title,
        "mapx": "126.977",
        "mapy": "37.579",
    }


class PolicyTests(unittest.TestCase):
    def test_palace_is_core_place_but_not_hanok(self):
        self.assertEqual(
            policy.classify(place("126508", "HS010100", "경복궁")),
            ("INCLUDE", "STRONG_TAXONOMY_PENDING_DETAIL_GATE", "CORE_TRADITIONAL_PLACE"),
        )

    def test_broad_cafe_requires_detail_evidence(self):
        self.assertEqual(policy.classify(place("1", "FD050100", "한옥 찻집"))[0], "REVIEW")
        self.assertEqual(policy.classify(place("2", "FD050100", "일반 카페"))[0], "EXCLUDE")

    def test_rescue_search_does_not_include_without_evidence(self):
        self.assertEqual(policy.classify(place("3", "AC010100", "북촌 한옥호텔"))[0], "REVIEW")
        self.assertEqual(policy.classify(place("4", "C01150001", "궁궐 여행 코스"))[0], "EXCLUDE")

    def test_invalid_coordinate_is_excluded(self):
        record = place("5", "HS010100", "궁궐")
        record["mapx"] = "0"
        self.assertEqual(policy.classify(record)[1], "SOURCE_INVALID")


if __name__ == "__main__":
    unittest.main()
