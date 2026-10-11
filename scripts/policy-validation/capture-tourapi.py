#!/usr/bin/env python3
"""Capture a reproducible, credential-free TourAPI candidate snapshot."""

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


CODES = (
    "HS010100 HS010200 HS010300 HS010400 HS010500 HS010600 EX010100 AC030200 VE040100 VE040200 "
    "HS010700 HS011100 HS011200 EX040200 HS020100 HS020300 HS030100 VE070100 VE070200 VE070300 "
    "VE090100 VE090400 FD040400 FD050200 SH050100 SH060100 SH060200 NA040700 VE010100 VE010900 "
    "EX060100 EX060300 FD050100 EX030100"
).split()
KEYWORDS = ("한옥", "고택", "종택", "고궁", "궁궐", "전통마을", "한옥스테이", "전통시장")
LEGACY_FILTERS = (
    ("12", "A02", "A0201", "A02010700"),
    ("12", "A02", "A0201", "A02010100"),
    ("12", "A02", "A0201", "A02010300"),
    ("12", "A02", "A0201", "A02010800"),
    ("39", "A05", "A0502", "A05020900"),
    ("32", "B02", "B0201", "B02011600"),
    ("12", "A02", "A0203", "A02030400"),
    ("38", "A04", "A0401", "A04010200"),
)
FIELDS = ("contentid", "contenttypeid", "title", "addr1", "areacode", "sigungucode", "cat1", "cat2", "cat3", "lclsSystm3", "mapx", "mapy", "modifiedtime", "firstimage", "cpyrhtDivCd")


def fetch(operation, filters, page, rows, key):
    params = {
        "serviceKey": key,
        "MobileOS": "ETC",
        "MobileApp": "OnMaru",
        "_type": "json",
        "pageNo": str(page),
        "numOfRows": str(rows),
    }
    params.update(filters)
    url = "https://apis.data.go.kr/B551011/KorService2/" + operation + "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                document = json.load(response)
            header = document["response"]["header"]
            if header["resultCode"] != "0000":
                raise ValueError(f"provider resultCode={header['resultCode']}")
            body = document["response"]["body"]
            items = body.get("items", {}).get("item", [])
            if isinstance(items, dict):
                items = [items]
            return int(body["totalCount"]), [{field: item.get(field) for field in FIELDS} for item in items]
        except (urllib.error.URLError, TimeoutError, ValueError, KeyError) as error:
            if attempt == 3:
                raise RuntimeError(f"{operation} {filters} page {page}: {type(error).__name__}") from error
            time.sleep(2 ** attempt)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--output", required=True)
    parser.add_argument("--required-keyword", action="append", default=[],
                        help="Current verified filming place title outside the base filters; repeatable")
    args = parser.parse_args()
    key = os.environ.get("ONMARU_TOURAPI_SERVICE_KEY")
    if not key:
        parser.error("ONMARU_TOURAPI_SERVICE_KEY is required")
    captures = []
    queries = [("areaBasedList2", {"lclsSystm3": code}) for code in CODES]
    keywords = tuple(dict.fromkeys((*KEYWORDS, *args.required_keyword)))
    if any(not keyword.strip() for keyword in keywords):
        parser.error("required keywords must be nonblank")
    queries += [("searchKeyword2", {"keyword": keyword}) for keyword in keywords]
    queries += [("areaBasedList2", dict(zip(("contentTypeId", "cat1", "cat2", "cat3"), values)))
                for values in LEGACY_FILTERS]
    for operation, filters in queries:
        value = ":".join(filters.values())
        total = None
        page = 1
        pages = []
        records = []
        while True:
            reported, items = fetch(operation, filters, page, args.rows, key)
            if 'lclsSystm3' in filters and any(item.get('lclsSystm3') != filters['lclsSystm3'] for item in items):
                raise ValueError(f"classification mismatch for {value} page {page}")
            if 'contentTypeId' in filters and any(
                    any(item.get(field) != expected for field, expected in (
                        ('contenttypeid', filters['contentTypeId']), ('cat1', filters['cat1']),
                        ('cat2', filters['cat2']), ('cat3', filters['cat3']))) for item in items):
                raise ValueError(f"legacy classification mismatch for {value} page {page}")
            if total is None:
                total = reported
            elif total != reported:
                raise ValueError(f"totalCount changed for {value}: {total} -> {reported}")
            if len(items) > args.rows or (page - 1) * args.rows + len(items) > total:
                raise ValueError(f"page overflow for {value} page {page}")
            pages.append({"page": page, "received": len(items)})
            records.extend(items)
            if page * args.rows >= total:
                break
            if not items:
                raise ValueError(f"empty incomplete page for {value} page {page}")
            page += 1
            time.sleep(0.2)
        if len(records) != total:
            raise ValueError(f"incomplete {value}: {len(records)} != {total}")
        captures.append({"operation": operation, "filters": filters, "filter": value, "totalCount": total, "pages": pages, "records": records})
        print(f"{operation} {value}: {total} records / {len(pages)} pages", file=sys.stderr)
    result = {"capturedAt": dt.datetime.now(dt.timezone.utc).isoformat(), "rowsPerPage": args.rows, "captures": captures}
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as handle:
        json.dump(result, handle, ensure_ascii=False, separators=(",", ":"))
    print(json.dumps({"capturedAt": result["capturedAt"], "requests": sum(len(x["pages"]) for x in captures), "records": sum(x["totalCount"] for x in captures)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
