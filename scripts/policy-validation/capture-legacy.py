#!/usr/bin/env python3
"""Capture stable, public legacy API assertions for later regression checks."""

import datetime as dt
import json
import urllib.error
import urllib.request


BASE = "https://api.onmaru.site"
PATHS = (
    "/api/v1/hanoks?limit=2",
    "/api/v1/hanoks/p-tourapi-1005548",
    "/api/v1/hanoks/screen-hanok",
    "/api/v1/map/places?lat=37.5796&lng=126.9770&radius=1500&limit=1000",
    "/api/v1/map/info/places?category=SPOT&limit=2",
    "/api/v1/places/p-tourapi-126508",
    "/api/v1/saved-resources",
    "/api/v1/home/popular-regions",
    "/api/v1/home/trending-sounds?language=ko-KR&limit=2",
)


def main():
    responses = []
    for path in PATHS:
        try:
            response = urllib.request.urlopen(BASE + path, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = json.load(response)
            status = response.status
        items = payload.get("items", [])
        responses.append({
            "path": path,
            "status": status,
            "schemaVersion": payload.get("schemaVersion"),
            "totalCount": payload.get("totalCount", payload.get("total")),
            "itemCount": len(items),
            "firstPlaceIds": [item["placeId"] for item in items[:3] if "placeId" in item],
            "containsGyeongbokgung": "p-tourapi-126508" in json.dumps(payload),
        })
    print(json.dumps({"capturedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "baseUrl": BASE, "responses": responses}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
