"""Deterministic fixture-only extractor; accepts one JSON bundle on stdin."""

import json
import sys


def main() -> None:
    bundle = json.load(sys.stdin)
    place = bundle.get("placeName")
    region = bundle.get("region")
    evidence = bundle.get("evidence") or []
    expected = "서울 종로구 경복궁에서 드라마 별빛을 촬영했다."
    matches = [item for item in evidence if expected in item.get("excerpt", "")
               and place == "경복궁" and region == "서울 종로구"]
    if not matches:
        result = {"resultStatus": "UNCERTAIN", "candidates": [],
                  "ambiguityReason": "FIXTURE_HAS_NO_EXPLICIT_FILMING_CLAIM"}
    else:
        result = {"resultStatus": "MATCH", "candidates": [{
            "title": "별빛", "workType": "DRAMA", "placeId": bundle["placeId"],
            "placeName": place, "region": region, "relationType": "FILMING_LOCATION",
            "evidence": [{"evidenceId": matches[0]["evidenceId"], "quote": expected}],
            "tags": [], "summaries": []
        }]}
    json.dump(result, sys.stdout, ensure_ascii=False)


if __name__ == "__main__":
    main()
